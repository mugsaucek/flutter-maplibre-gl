import Flutter
import MapLibre
import QuartzCore

/// Ход меток и перелёт камеры — на кадрах самой карты, а не из Dart.
///
/// Своё дополнение к плагину (см. README_LOCAL.md). Dart отдаёт ход ОДНИМ
/// вызовом — откуда, куда, сколько и по какой кривой, — а дальше каждый кадр
/// считается здесь, по часам экрана. Из Dart шаги шли каналом: задержка его
/// плавала, натив рисует по своим часам, и ход на экране выходил неровным.
///
/// Кривые приходят из Dart четвёркой точек кубической Безье — той же, что у
/// `Cubic` во Flutter, — и решаются здесь тем же способом: ход совпадает с
/// прежним один в один.
final class MapMotionAnimator: NSObject {
    /// Замер (integration_test приложения): время перекладки источника меток и сколько раз, пока меряем.
    static var benchShapeMs: Double?
    static var benchShapeCalls = 0
    static var benchFeatures = 0

    private weak var mapView: MLNMapView?

    init(mapView: MLNMapView) {
        self.mapView = mapView
    }

    // MARK: - Метки

    private struct Moving {
        let feature: MLNPointFeature
        let base: [String: Any]
        let from: [Double]  // lat, lon, opacity, scale
        let to: [Double]
        let fade: (Double, Double)
        let leaving: Bool
    }

    private var marksLink: CADisplayLink?
    private var marksStart: CFTimeInterval = 0
    private var marksDuration: Double = 0
    private var marksCurve = Cubic(0.215, 0.61, 0.355, 1.0)
    private var moving: [Moving] = []
    private var still: [MLNPointFeature] = []
    private var source = ""
    private var marksResult: FlutterResult?

    /// `marks#transition`: все метки — в ОДНОМ источнике, и на каждом кадре
    /// хода он получает весь набор разом: неподвижные на своих местах, едущие
    /// — на этом кадре. Каждое обновление применяется целиком, и метке негде
    /// пропасть: двумя источниками (с выдержкой на стыке и без неё) кластеры
    /// моргали, а дубль на стыке давал тени двойную плотность.
    ///
    /// `delayMs` — через сколько ход тронется. `syncGlide` — идёт перелёт:
    /// старт считается здесь, от его настоящего начала и длины, так, чтобы ход
    /// кончился за `leadMs` до прилёта. Считай его Dart, он считал бы от своих
    /// часов, а перелёт трогается позже — когда команда дойдёт до натива, — и
    /// распад доигрывал уже на стоящей карте. До старта метки стоят как стояли.
    func transition(_ args: [String: Any], result: @escaping FlutterResult) {
        finishMarks(applyFinal: false)
        guard let source = args["source"] as? String else {
            result(false)
            return
        }
        self.source = source
        marksDuration = (args["durationMs"] as? Double ?? 0) / 1000
        var delay = max(0, (args["delayMs"] as? Double ?? 0) / 1000)
        if args["syncGlide"] as? Bool == true, isGliding {
            let lead = (args["leadMs"] as? Double ?? 0) / 1000
            let end = glideStart + glideDuration
            delay = max(0, end - lead - marksDuration - CACurrentMediaTime())
        }
        if let c = args["curve"] as? [Double], c.count == 4 { marksCurve = Cubic(c[0], c[1], c[2], c[3]) }

        still = (args["still"] as? [[String: Any]] ?? []).map { f in
            let feature = MLNPointFeature()
            feature.identifier = f["id"] as? String
            feature.coordinate = CLLocationCoordinate2D(latitude: f["lat"] as? Double ?? 0, longitude: f["lon"] as? Double ?? 0)
            feature.attributes = f["props"] as? [String: Any] ?? [:]
            return feature
        }
        moving = (args["moving"] as? [[String: Any]] ?? []).map { f in
            let feature = MLNPointFeature()
            feature.identifier = f["id"] as? String
            let fade = f["fade"] as? [Double] ?? [0, 1]
            return Moving(
                feature: feature,
                base: f["props"] as? [String: Any] ?? [:],
                from: f["from"] as? [Double] ?? [0, 0, 1, 1],
                to: f["to"] as? [Double] ?? [0, 0, 1, 1],
                fade: (fade[0], fade[1]),
                leaving: f["leaving"] as? Bool ?? false
            )
        }

        marksResult = result
        if moving.isEmpty || marksDuration <= 0 {
            finishMarks(applyFinal: true)
            return
        }
        marksStart = CACurrentMediaTime() + delay
        if delay <= 0 { place(at: 0) }
        let link = CADisplayLink(target: self, selector: #selector(linkStep))
        link.add(to: .main, forMode: .common)
        marksLink = link
    }

    /// Ход поставлен, но ещё не тронулся (ждёт своего старта в перелёте): на
    /// карте всё ещё то, с чего он начнётся.
    var marksPending: Bool { marksLink != nil && CACurrentMediaTime() < marksStart }

    var isMarksRunning: Bool { marksLink != nil }

    /// Когда шаг последний раз делала сама карта, в начале своего кадра.
    private var renderStepAt: CFTimeInterval = 0

    /// Карта начинает кадр — шаг перелёта и хода меток здесь.
    ///
    /// Шаг от своего CADisplayLink приходился на время после отрисовки кадра картой, а просьба перерисовать
    /// доходила до неё следующим оборотом цикла — и карта рисовала через кадр: перелёт и ход меток шли на 30
    /// кадрах вместо 60 (замер на iPhone XS, 25.11.2026). Шаг из начала кадра карты держит её в каждом кадре.
    func renderWillStart() {
        guard glideLink != nil || marksLink != nil else { return }
        renderStepAt = CACurrentMediaTime()
        if glideLink != nil { glideStep() }
        if marksLink != nil { marksStep() }
    }

    /// CADisplayLink — только подстраховка: двигает ход, если карта сама давно не начинала кадр (первый шаг,
    /// пропущенный кадр). Иначе источник меток перекладывался бы дважды за кадр.
    @objc private func linkStep() {
        if CACurrentMediaTime() - renderStepAt < 0.025 { return }
        if glideLink != nil { glideStep() }
        if marksLink != nil { marksStep() }
    }

    @objc private func marksStep() {
        let now = CACurrentMediaTime()
        // Ещё не тронулись — метки стоят как стояли; стартовое положение
        // встаёт одним кадром ровно в момент старта.
        if now < marksStart { return }
        let t = min(1, (now - marksStart) / marksDuration)
        if t >= 1 {
            finishMarks(applyFinal: true)
            return
        }
        place(at: marksCurve.transform(t))
    }

    private func place(at e: Double) {
        for m in moving {
            let fadeT = m.fade.1 > m.fade.0 ? min(1, max(0, (e - m.fade.0) / (m.fade.1 - m.fade.0))) : (e >= m.fade.1 ? 1 : 0)
            m.feature.coordinate = CLLocationCoordinate2D(
                latitude: lerp(m.from[0], m.to[0], e),
                longitude: lerp(m.from[1], m.to[1], e)
            )
            var attributes = m.base
            attributes["o"] = lerp(m.from[2], m.to[2], fadeT)
            attributes["s"] = lerp(m.from[3], m.to[3], e)
            m.feature.attributes = attributes
        }
        setShape(still + moving.map { $0.feature })
    }

    /// Остановить ход и ответить Dart. [applyFinal] — поставить метки на места,
    /// без уходящих. Прерванный ход оставляет источник как есть.
    private func finishMarks(applyFinal: Bool) {
        marksLink?.invalidate()
        marksLink = nil
        if applyFinal {
            var all = still
            for m in moving where !m.leaving {
                m.feature.coordinate = CLLocationCoordinate2D(latitude: m.to[0], longitude: m.to[1])
                var attributes = m.base
                attributes["o"] = m.to[2]
                attributes["s"] = m.to[3]
                m.feature.attributes = attributes
                all.append(m.feature)
            }
            setShape(all)
        }
        let pending = marksResult
        marksResult = nil
        pending?(applyFinal)
    }

    private func setShape(_ features: [MLNPointFeature]) {
        guard !source.isEmpty,
              let shapeSource = mapView?.style?.source(withIdentifier: source) as? MLNShapeSource
        else { return }
        let started = MapMotionAnimator.benchShapeMs != nil ? CACurrentMediaTime() : 0
        shapeSource.shape = MLNShapeCollectionFeature(shapes: features)
        if let spent = MapMotionAnimator.benchShapeMs {
            MapMotionAnimator.benchShapeMs = spent + (CACurrentMediaTime() - started) * 1000
            MapMotionAnimator.benchShapeCalls += 1
            MapMotionAnimator.benchFeatures = max(MapMotionAnimator.benchFeatures, features.count)
        }
    }

    // MARK: - Камера

    private var glideLink: CADisplayLink?
    private var glideStart: CFTimeInterval = 0
    private var glideDuration: Double = 0
    private var glideCurve = Cubic(0.645, 0.045, 0.355, 1.0)
    private var glideLat = 0.0
    private var glideLon = 0.0
    private var glideFrom = CGPoint.zero
    private var glideTo = CGPoint.zero
    private var glideFromZoom = 0.0
    private var glideToZoom = 0.0
    private var glideResult: FlutterResult?

    /// Перелёт идёт — контроллер не шлёт в Dart «камера начала/кончила»
    /// на каждом его кадре (см. `MapLibreMapController.regionWillChange`).
    private(set) var isGliding = false

    /// Перелёт доехал — контроллер шлёт одно «камера встала».
    var onGlideDone: (() -> Void)?

    /// Тихий режим по просьбе Dart (`camera#quiet`): камеру ставит Dart на
    /// каждом кадре — карта едет за шторкой, — и сообщения о камере те же, что
    /// в перелёте: одно «начала», одно «встала».
    var quiet = false

    /// Сообщения о камере приглушены: идёт перелёт или тихий режим.
    var isQuiet: Bool { isGliding || quiet }

    /// `camera#glide`: точка карты ([lat], [lon]) едет по экрану по прямой из
    /// `from` в `to`, масштаб — монотонно из `fromZoom` в `toZoom`. Тот же
    /// счёт, что `glideCamera` в Dart. Ответ — когда доехали (`true`) или
    /// когда ход оборвали (`false`).
    func glide(_ args: [String: Any], result: @escaping FlutterResult) {
        stopGlide()
        glideLat = args["lat"] as? Double ?? 0
        glideLon = args["lon"] as? Double ?? 0
        glideFrom = CGPoint(x: args["fromX"] as? Double ?? 0, y: args["fromY"] as? Double ?? 0)
        glideTo = CGPoint(x: args["toX"] as? Double ?? 0, y: args["toY"] as? Double ?? 0)
        glideFromZoom = args["fromZoom"] as? Double ?? 0
        // Старт — от того, где камера стоит СЕЙЧАС: Dart считал его по рамке, пока предыдущий перелёт ещё летел,
        // и новый перелёт начинался с прыжка (быстрые нажатия по кластеру).
        if args["fromCurrent"] as? Bool == true, let mapView = mapView {
            glideFromZoom = mapView.zoomLevel
            glideFrom = mapView.convert(CLLocationCoordinate2D(latitude: glideLat, longitude: glideLon), toPointTo: mapView)
        }
        glideToZoom = args["toZoom"] as? Double ?? 0
        glideDuration = (args["durationMs"] as? Double ?? 0) / 1000
        if let c = args["curve"] as? [Double], c.count == 4 { glideCurve = Cubic(c[0], c[1], c[2], c[3]) }
        glideResult = result
        glideStart = CACurrentMediaTime()
        isGliding = true
        if glideDuration <= 0 {
            applyGlide(t: 1)
            finishGlide(done: true)
            return
        }
        let link = CADisplayLink(target: self, selector: #selector(linkStep))
        link.add(to: .main, forMode: .common)
        glideLink = link
    }

    @objc private func glideStep() {
        let t = min(1, (CACurrentMediaTime() - glideStart) / glideDuration)
        applyGlide(t: t)
        if t >= 1 { finishGlide(done: true) }
    }

    private func applyGlide(t: Double) {
        guard let mapView = mapView else { return }
        let e = glideCurve.transform(t)
        let zoom = t >= 1 ? glideToZoom : glideFromZoom + (glideToZoom - glideFromZoom) * e
        let at = CGPoint(
            x: glideFrom.x + (glideTo.x - glideFrom.x) * e,
            y: glideFrom.y + (glideTo.y - glideFrom.y) * e
        )
        let center = MapMotionAnimator.cameraTarget(
            lat: glideLat, lon: glideLon, at: at, size: mapView.bounds.size, zoom: zoom
        )
        mapView.setCenter(center, zoomLevel: zoom, animated: false)
    }

    func stopGlide() {
        if glideLink != nil || glideResult != nil { finishGlide(done: false) }
    }

    private func finishGlide(done: Bool) {
        glideLink?.invalidate()
        glideLink = nil
        let wasGliding = isGliding
        isGliding = false
        let pending = glideResult
        glideResult = nil
        // Сначала «встала», потом ответ перелёту: Dart, узнав о конце хода,
        // спрашивает карту о месте — она уже должна о нём знать.
        if done && wasGliding { onGlideDone?() }
        pending?(done)
    }

    /// Всё остановить — карта уходит.
    func dispose() {
        finishMarks(applyFinal: false)
        stopGlide()
    }

    /// Куда поставить центр, чтобы точка ([lat], [lon]) встала в [at] при
    /// масштабе [zoom]. Мир на масштабе — `512 · 2^zoom` точек, как у Dart.
    static func cameraTarget(lat: Double, lon: Double, at: CGPoint, size: CGSize, zoom: Double) -> CLLocationCoordinate2D {
        let world = 512 * pow(2, zoom)
        let dx = Double(at.x - size.width / 2)
        let dy = Double(at.y - size.height / 2)
        let clamped = min(85, max(-85, lat))
        let y = log(tan(Double.pi / 4 + clamped * Double.pi / 360)) + dy * 2 * Double.pi / world
        return CLLocationCoordinate2D(
            latitude: (2 * atan(exp(y)) - Double.pi / 2) * 180 / Double.pi,
            longitude: lon - dx * 360 / world
        )
    }

    private func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }
}

/// Кубическая Безье тем же счётом, что `Cubic.transform` во Flutter.
struct Cubic {
    let a: Double
    let b: Double
    let c: Double
    let d: Double

    init(_ a: Double, _ b: Double, _ c: Double, _ d: Double) {
        self.a = a
        self.b = b
        self.c = c
        self.d = d
    }

    private static func evaluate(_ a: Double, _ b: Double, _ m: Double) -> Double {
        3 * a * (1 - m) * (1 - m) * m + 3 * b * (1 - m) * m * m + m * m * m
    }

    func transform(_ t: Double) -> Double {
        if t <= 0 { return 0 }
        if t >= 1 { return 1 }
        var start = 0.0
        var end = 1.0
        while true {
            let mid = (start + end) / 2
            let estimate = Cubic.evaluate(a, c, mid)
            if abs(t - estimate) < 0.001 { return Cubic.evaluate(b, d, mid) }
            if estimate < t { start = mid } else { end = mid }
        }
    }
}
