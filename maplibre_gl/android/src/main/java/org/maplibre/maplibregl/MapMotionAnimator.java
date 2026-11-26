package org.maplibre.maplibregl;

import android.view.Choreographer;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.flutter.plugin.common.MethodChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

/**
 * Ход меток и перелёт камеры — на кадрах самой карты, а не из Dart.
 *
 * <p>Своё дополнение к плагину (см. README_LOCAL.md), близнец MapMotionAnimator.swift. Dart отдаёт
 * ход одним вызовом, каждый кадр считается здесь по Choreographer. Кривые — четвёрка точек
 * кубической Безье, тем же счётом, что `Cubic` во Flutter.
 */
final class MapMotionAnimator {
  interface Host {
    @Nullable
    Style style();

    @Nullable
    MapLibreMap map();

    @Nullable
    MapView view();

    float density();
  }

  private final Host host;

  MapMotionAnimator(Host host) {
    this.host = host;
  }

  // ── Метки ─────────────────────────────────────────────────────────────────

  private static final class Moving {
    String id;
    JsonObject base;
    /** Начало JSON метки до координат и свойства без «o»/«s» — собираются один раз за ход. */
    String head;
    String props;
    double[] from; // lat, lon, opacity, scale
    double[] to;
    double fadeFrom;
    double fadeTo;
    boolean leaving;
  }

  private final List<Feature> still = new ArrayList<>();
  /** Неподвижные метки JSON-ом — один раз за ход: на кадре дописываются только едущие. */
  private String stillJson = "";
  /** Источник на время хода: искать его в стиле на каждом кадре незачем. */
  @Nullable private GeoJsonSource sourceRef;
  @Nullable private Style sourceStyle;
  private final List<Moving> moving = new ArrayList<>();
  private String source = "";
  private long marksStart;
  private double marksDuration;
  private double[] marksCurve = {0.215, 0.61, 0.355, 1.0};
  @Nullable private MethodChannel.Result marksResult;
  private boolean marksRunning;

  private final Choreographer.FrameCallback marksFrame =
      new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
          if (!marksRunning) return;
          // Подстраховка: шагает сама карта в начале кадра (renderWillStart), здесь — только если она стоит.
          if (!renderDriven()) marksTick();
          if (marksRunning) Choreographer.getInstance().postFrameCallback(this);
        }
      };

  /** Шаг хода меток по текущему времени. */
  private void marksTick() {
    long now = System.nanoTime();
    // Ещё не тронулись — метки стоят как стояли.
    if (now < marksStart) return;
    double t = Math.min(1, (now - marksStart) / 1e9 / marksDuration);
    if (t >= 1) {
      finishMarks(true);
      return;
    }
    setFrame(cubic(marksCurve, t));
  }

  /** Когда шаг последний раз делала сама карта, в начале своего кадра. */
  private long renderStepAt;

  /**
   * Карта начинает кадр — шаг перелёта и хода меток здесь (см. MapMotionAnimator.swift, renderWillStart): шаг от
   * Choreographer приходился не в такт кадрам карты, и она пропускала кадры.
   */
  void renderWillStart() {
    // Источник меток синхронный (`source#synchronous`): шаг ведёт такт экрана (Choreographer) — ровно раз в кадр.
    // Событие «карта начала кадр» приходит в главный поток с опозданием, и шаги от него шли неровно (Pixel 8,
    // Hybrid Composition++, 26.11.2026).
    if (vsyncDriven) return;
    if (!glideRunning && !marksRunning) return;
    renderStepAt = System.nanoTime();
    if (glideRunning) glideTick();
    if (marksRunning) marksTick();
  }

  /** Карта сама шагала недавно — Choreographer не нужен; 25 мс — полтора кадра на 60 Гц. */
  /** Шаг — только от такта экрана; см. [renderWillStart]. */
  boolean vsyncDriven = true;

  private boolean renderDriven() {
    if (vsyncDriven) return false;
    return System.nanoTime() - renderStepAt < 25_000_000L;
  }

  /** Ход поставлен, но ещё не тронулся — см. MapMotionAnimator.swift, marksPending. */
  boolean isMarksRunning() {
    return marksRunning;
  }

  boolean marksPending() {
    return marksRunning && System.nanoTime() < marksStart;
  }

  /** `marks#transition` — все метки в одном источнике, см. MapMotionAnimator.swift. */
  @SuppressWarnings("unchecked")
  void transition(@NonNull Map<String, Object> args, @NonNull MethodChannel.Result result) {
    finishMarks(false);
    Object s = args.get("source");
    if (!(s instanceof String)) {
      result.success(false);
      return;
    }
    source = (String) s;
    marksDuration = number(args.get("durationMs"), 0) / 1000;
    double delay = Math.max(0, number(args.get("delayMs"), 0) / 1000);
    // Идёт перелёт — старт от его настоящего начала и длины (см.
    // MapMotionAnimator.swift, syncGlide).
    if (Boolean.TRUE.equals(args.get("syncGlide")) && gliding) {
      double lead = number(args.get("leadMs"), 0) / 1000;
      double end = glideStart / 1e9 + glideDuration;
      delay = Math.max(0, end - lead - marksDuration - System.nanoTime() / 1e9);
    }
    double[] curve = doubles(args.get("curve"));
    if (curve != null && curve.length == 4) marksCurve = curve;

    still.clear();
    Object stillList = args.get("still");
    if (stillList instanceof List) {
      for (Object o : (List<Object>) stillList) {
        Map<String, Object> f = (Map<String, Object>) o;
        still.add(
            Feature.fromGeometry(
                Point.fromLngLat(number(f.get("lon"), 0), number(f.get("lat"), 0)),
                props(f.get("props")),
                (String) f.get("id")));
      }
    }
    moving.clear();
    Object movingList = args.get("moving");
    if (movingList instanceof List) {
      for (Object o : (List<Object>) movingList) {
        Map<String, Object> f = (Map<String, Object>) o;
        Moving mv = new Moving();
        mv.id = (String) f.get("id");
        mv.base = props(f.get("props"));
        mv.from = doubles(f.get("from"));
        mv.to = doubles(f.get("to"));
        double[] fade = doubles(f.get("fade"));
        mv.fadeFrom = fade != null ? fade[0] : 0;
        mv.fadeTo = fade != null ? fade[1] : 1;
        mv.leaving = Boolean.TRUE.equals(f.get("leaving"));
        if (mv.from == null || mv.to == null) continue;
        mv.head =
            "{\"type\":\"Feature\",\"id\":"
                + new com.google.gson.JsonPrimitive(mv.id == null ? "" : mv.id)
                + ",\"geometry\":{\"type\":\"Point\",\"coordinates\":[";
        String base = mv.base.toString();
        // «{...}» без закрывающей скобки — «o» и «s» дописываются на кадре.
        mv.props = base.length() > 2 ? base.substring(0, base.length() - 1) + "," : "{";
        moving.add(mv);
      }
    }

    marksResult = result;
    if (moving.isEmpty() || marksDuration <= 0) {
      finishMarks(true);
      return;
    }
    StringBuilder json = new StringBuilder();
    for (Feature f : still) {
      if (json.length() > 0) json.append(',');
      json.append(f.toJson());
    }
    stillJson = json.toString();
    sourceRef = null;
    marksStart = System.nanoTime() + (long) (delay * 1e9);
    if (delay <= 0) setFrame(0);
    marksRunning = true;
    Choreographer.getInstance().postFrameCallback(marksFrame);
  }

  private Feature featureAt(Moving m, double e) {
    double fadeT =
        m.fadeTo > m.fadeFrom
            ? Math.min(1, Math.max(0, (e - m.fadeFrom) / (m.fadeTo - m.fadeFrom)))
            : (e >= m.fadeTo ? 1 : 0);
    JsonObject p = m.base.deepCopy();
    p.addProperty("o", lerp(m.from[2], m.to[2], fadeT));
    p.addProperty("s", lerp(m.from[3], m.to[3], e));
    return Feature.fromGeometry(
        Point.fromLngLat(lerp(m.from[1], m.to[1], e), lerp(m.from[0], m.to[0], e)), p, m.id);
  }

  /**
   * Кадр хода строкой: неподвижные — готовым JSON, едущие — дописываются. Раньше на каждом кадре каждая едущая
   * метка копировала свои свойства (Gson deepCopy), а весь набор пересобирался Java-объектами, которые натив
   * потом обходил через JNI; строку натив разбирает сам. Картинка та же.
   */
  /** Замер (integration_test приложения): когда ставились шаги хода меток, нс; {@code null} — не меряем. */
  static java.util.List<Long> benchSteps;

  private void setFrame(double e) {
    if (benchSteps != null) benchSteps.add(System.nanoTime());
    StringBuilder json = new StringBuilder(stillJson.length() + moving.size() * 256 + 64);
    json.append("{\"type\":\"FeatureCollection\",\"features\":[").append(stillJson);
    boolean first = stillJson.isEmpty();
    for (Moving m : moving) {
      if (!first) json.append(',');
      first = false;
      double fadeT =
          m.fadeTo > m.fadeFrom
              ? Math.min(1, Math.max(0, (e - m.fadeFrom) / (m.fadeTo - m.fadeFrom)))
              : (e >= m.fadeTo ? 1 : 0);
      json.append(m.head)
          .append(lerp(m.from[1], m.to[1], e))
          .append(',')
          .append(lerp(m.from[0], m.to[0], e))
          .append("]},\"properties\":")
          .append(m.props)
          .append("\"o\":")
          .append(lerp(m.from[2], m.to[2], fadeT))
          .append(",\"s\":")
          .append(lerp(m.from[3], m.to[3], e))
          .append("}}");
    }
    json.append("]}");
    GeoJsonSource geoJson = source();
    if (geoJson != null) geoJson.setGeoJson(json.toString());
  }

  /** Источник меток: на время хода — запомненный; сменился стиль — ищется заново. */
  @Nullable
  private GeoJsonSource source() {
    Style style = host.style();
    if (source.isEmpty() || style == null || !style.isFullyLoaded()) {
      sourceRef = null;
      return null;
    }
    if (sourceRef == null || sourceStyle != style) {
      sourceRef = style.getSourceAs(source);
      sourceStyle = style;
    }
    return sourceRef;
  }

  /** Остановить ход и ответить Dart: [applyFinal] — поставить метки на места, без уходящих. */
  private void finishMarks(boolean applyFinal) {
    if (marksRunning) Choreographer.getInstance().removeFrameCallback(marksFrame);
    marksRunning = false;
    if (applyFinal) {
      List<Feature> all = new ArrayList<>(still);
      for (Moving m : moving) {
        if (!m.leaving) all.add(featureAt(m, 1));
      }
      setShape(all);
    }
    MethodChannel.Result pending = marksResult;
    marksResult = null;
    if (pending != null) pending.success(applyFinal);
  }

  private void setShape(List<Feature> features) {
    Style style = host.style();
    if (source.isEmpty() || style == null || !style.isFullyLoaded()) return;
    GeoJsonSource geoJson = style.getSourceAs(source);
    if (geoJson == null) return;
    geoJson.setGeoJson(FeatureCollection.fromFeatures(features));
  }

  // ── Камера ────────────────────────────────────────────────────────────────

  private double glideLat;
  private double glideLon;
  private double glideFromX;
  private double glideFromY;
  private double glideToX;
  private double glideToY;
  private double glideFromZoom;
  private double glideToZoom;
  private double glideDuration;
  private long glideStart;
  private double[] glideCurve = {0.645, 0.045, 0.355, 1.0};
  @Nullable private MethodChannel.Result glideResult;
  private boolean glideRunning;

  /** Перелёт идёт — см. MapMotionAnimator.swift, isGliding. */
  private boolean gliding;

  /** Перелёт доехал — контроллер шлёт одно «камера встала». */
  @Nullable Runnable onGlideDone;

  boolean isGliding() {
    return gliding;
  }

  /** Тихий режим по просьбе Dart (`camera#quiet`) — см. MapMotionAnimator.swift, quiet. */
  boolean quiet;

  /** Сообщения о камере приглушены: идёт перелёт или тихий режим. */
  boolean isQuiet() {
    return gliding || quiet;
  }

  private final Choreographer.FrameCallback glideFrame =
      new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
          if (!glideRunning) return;
          if (!renderDriven()) glideTick();
          if (glideRunning) Choreographer.getInstance().postFrameCallback(this);
        }
      };

  /** Шаг перелёта по текущему времени. */
  private void glideTick() {
    double t = Math.min(1, (System.nanoTime() - glideStart) / 1e9 / glideDuration);
    applyGlide(t);
    if (t >= 1) finishGlide(true);
  }

  /** `camera#glide` — см. MapMotionAnimator.swift. */
  // ─── Камера за шторкой ────────────────────────────────────────────────────

  private double followLat;
  private double followLon;
  private double followZoom;
  private boolean followPending;

  /**
   * `camera#follow`: камера за шторкой — сразу, как пришла: у карты почти целый кадр, чтобы успеть к выводу
   * вместе со шторкой. Откладывать до такта экрана хуже: кадр карты выходил ещё на кадр позже шторки (Pixel 8,
   * 26.11.2026).
   */
  void follow(@NonNull Map<String, Object> args) {
    followLat = number(args.get("lat"), 0);
    followLon = number(args.get("lon"), 0);
    followZoom = number(args.get("zoom"), 0);
    followPending = true;
    applyFollow();
  }

  private void applyFollow() {
    if (!followPending) return;
    followPending = false;
    MapLibreMap map = host.map();
    if (map == null) return;
    map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(followLat, followLon), followZoom));
  }

  void glide(@NonNull Map<String, Object> args, @NonNull MethodChannel.Result result) {
    stopGlide();
    glideLat = number(args.get("lat"), 0);
    glideLon = number(args.get("lon"), 0);
    glideFromX = number(args.get("fromX"), 0);
    glideFromY = number(args.get("fromY"), 0);
    glideToX = number(args.get("toX"), 0);
    glideToY = number(args.get("toY"), 0);
    glideFromZoom = number(args.get("fromZoom"), 0);
    // Старт — от того, где камера стоит СЕЙЧАС (см. MapMotionAnimator.swift, fromCurrent).
    MapLibreMap current = host.map();
    if (Boolean.TRUE.equals(args.get("fromCurrent")) && current != null) {
      glideFromZoom = current.getCameraPosition().zoom;
      android.graphics.PointF at = current.getProjection().toScreenLocation(new LatLng(glideLat, glideLon));
      glideFromX = at.x / host.density();
      glideFromY = at.y / host.density();
    }
    glideToZoom = number(args.get("toZoom"), 0);
    glideDuration = number(args.get("durationMs"), 0) / 1000;
    double[] curve = doubles(args.get("curve"));
    if (curve != null && curve.length == 4) glideCurve = curve;
    glideResult = result;
    gliding = true;
    if (glideDuration <= 0) {
      applyGlide(1);
      finishGlide(true);
      return;
    }
    glideStart = System.nanoTime();
    glideRunning = true;
    Choreographer.getInstance().postFrameCallback(glideFrame);
  }

  private void applyGlide(double t) {
    MapLibreMap map = host.map();
    MapView view = host.view();
    if (map == null || view == null) return;
    double e = cubic(glideCurve, t);
    double zoom = t >= 1 ? glideToZoom : glideFromZoom + (glideToZoom - glideFromZoom) * e;
    double atX = glideFromX + (glideToX - glideFromX) * e;
    double atY = glideFromY + (glideToY - glideFromY) * e;
    // Размер — в тех же логических точках, что у Dart: мир на масштабе —
    // 512 · 2^zoom точек.
    double width = view.getWidth() / host.density();
    double height = view.getHeight() / host.density();
    double world = 512 * Math.pow(2, zoom);
    double dx = atX - width / 2;
    double dy = atY - height / 2;
    double lat = Math.max(-85, Math.min(85, glideLat));
    double y = Math.log(Math.tan(Math.PI / 4 + lat * Math.PI / 360)) + dy * 2 * Math.PI / world;
    double centerLat = (2 * Math.atan(Math.exp(y)) - Math.PI / 2) * 180 / Math.PI;
    double centerLon = glideLon - dx * 360 / world;
    map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(centerLat, centerLon), zoom));
  }

  void stopGlide() {
    if (glideRunning || glideResult != null) finishGlide(false);
  }

  private void finishGlide(boolean done) {
    if (glideRunning) Choreographer.getInstance().removeFrameCallback(glideFrame);
    glideRunning = false;
    boolean wasGliding = gliding;
    gliding = false;
    if (done && wasGliding && onGlideDone != null) onGlideDone.run();
    MethodChannel.Result pending = glideResult;
    glideResult = null;
    if (pending != null) pending.success(done);
  }

  /** Всё остановить — карта уходит. */
  void dispose() {
    finishMarks(false);
    stopGlide();
  }

  // ── Счёт ──────────────────────────────────────────────────────────────────

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }

  private static double evaluate(double a, double b, double m) {
    return 3 * a * (1 - m) * (1 - m) * m + 3 * b * (1 - m) * m * m + m * m * m;
  }

  /** Кубическая Безье тем же счётом, что `Cubic.transform` во Flutter. */
  static double cubic(double[] c, double t) {
    if (t <= 0) return 0;
    if (t >= 1) return 1;
    double start = 0;
    double end = 1;
    while (true) {
      double mid = (start + end) / 2;
      double estimate = evaluate(c[0], c[2], mid);
      if (Math.abs(t - estimate) < 0.001) return evaluate(c[1], c[3], mid);
      if (estimate < t) {
        start = mid;
      } else {
        end = mid;
      }
    }
  }

  private static double number(@Nullable Object o, double fallback) {
    return o instanceof Number ? ((Number) o).doubleValue() : fallback;
  }

  @Nullable
  private static double[] doubles(@Nullable Object o) {
    if (!(o instanceof List)) return null;
    List<?> list = (List<?>) o;
    double[] out = new double[list.size()];
    for (int i = 0; i < out.length; i++) out[i] = number(list.get(i), 0);
    return out;
  }

  @SuppressWarnings("unchecked")
  private static JsonObject props(@Nullable Object o) {
    JsonObject out = new JsonObject();
    if (!(o instanceof Map)) return out;
    for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
      Object v = e.getValue();
      if (v instanceof Number) {
        out.addProperty(e.getKey(), (Number) v);
      } else if (v instanceof String) {
        out.addProperty(e.getKey(), (String) v);
      } else if (v instanceof Boolean) {
        out.addProperty(e.getKey(), (Boolean) v);
      } else if (v instanceof List) {
        JsonArray array = new JsonArray();
        for (Object item : (List<Object>) v) {
          if (item instanceof Number) array.add((Number) item);
          else if (item instanceof String) array.add((String) item);
        }
        out.add(e.getKey(), array);
      }
    }
    return out;
  }
}
