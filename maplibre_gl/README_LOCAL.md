# Наши правки maplibre_gl

Открытый форк `maplibre/flutter-maplibre-gl`: `main` совпадает с апстримом (обновляется кнопкой Sync fork), ветка
`patched` — один наш коммит `patched` поверх него. Теги `v<версия апстрима>-patched.<n>`, например
`v0.28.1-patched.1`. Движок карты (`map_engine`) подключает пакет git-зависимостью по `https` с `path: maplibre_gl`.
Actions в форке выключены: CI апстрима к нашему коммиту не относится.

Зачем: ход меток и перелёт камеры на кадрах самой нативной карты. Снаружи до нативной карты плагин не пускает.

## Что добавлено

Все команды идут своим каналом `maplibre_gl/motion_<id>`, мимо Pigeon-сообщений плагина: так правки не трогают
сгенерированный код (`messages.g.*`, `pigeons/`).

| Файл | Что |
|---|---|
| `ios/.../MapMotionAnimator.swift` | **новый**: ход меток и перелёт камеры, шаг в начале кадра карты, CADisplayLink — подстраховка |
| `ios/.../MapLibreMapController.swift` | поле `motion`, канал `motionChannel` и `onMotionCall`, замер кадров, тихий режим камеры в делегате |
| `android/.../MapMotionAnimator.java` | **новый**: то же на Choreographer |
| `android/.../MapLibreMapController.java` | поле `motion`, канал `motionChannel` и `onMotionCall`, 120 Гц, синхронный источник, замер, тихий режим |
| `lib/src/maplibre_map.dart` | `controller._viewId = id` в `onPlatformViewCreated` |
| `lib/src/controller.dart` | `_viewId`, `_motionChannel` и методы ниже |

Методы контроллера: `transitionMarks`, `glideCamera`, `stopGlide`, `marksPending`, `followCamera`, `setCameraQuiet`,
`addImageRaw`, `removeImages`, `setSourceSynchronous`, `setGestureZoomRate`, `benchFramesStart`/`benchFramesStop`.

Кривые приходят из Dart четвёркой точек кубической Безье и решаются тем же счётом, что `Cubic.transform` во Flutter.
Счёт перелёта совпадает с `glideCamera` в `map_engine/lib/map_layout.dart`: мир на масштабе — `512 · 2^zoom` логических
точек.

## Что с 0.28 штатное

Эти правки были в 0.27.1 и ушли: в 0.28 то же есть у самого плагина, движок задаёт их настройками.

- Проявление символов: `MapLibreMap(symbolPlacementTransitionsEnabled: false)`.
- Дисковый кеш тайлов 200 МБ: `setMaximumAmbientCacheSize`.
- Hybrid Composition++ на Android: штатный `AndroidView` с `SurfaceView` выводится им сам, если в манифесте приложения
  есть `io.flutter.embedding.android.EnableHcpp`. Где HCPP нет — `androidView: AndroidMapView(surface:
  AndroidMapSurface.textureView)`.

## Один источник

`marks#transition` получает один `source` и на каждом кадре кладёт в него весь набор: неподвижные и едущие вместе. Что
пробовали и отвергли:
- **два источника без выдержки:** метка на стыке хода на кадр пропадала, кластеры моргали;
- **два источника с выдержкой** (метка 3 кадра в обоих): полупрозрачная тень рисовалась дважды и пульсировала.

`delayMs` — через сколько ход тронется; до старта метки стоят как стояли. `syncGlide` + `leadMs`: если идёт
`camera#glide`, старт считает натив от настоящего начала и длины перелёта, так, чтобы ход кончился за `leadMs` до
прилёта. `marks#pending` — поставлен ли ход и не тронулся ли ещё: Dart спрашивает это, прежде чем пересчитать ход в
перелёте (иначе распад игрался дважды).

## Шаг хода — в начале кадра карты

iOS: `mapViewWillStartRenderingFrame` → `MapMotionAnimator.renderWillStart`. Свой CADisplayLink срабатывал уже после
отрисовки, просьба перерисовать доходила до карты следующим оборотом цикла — карта рисовала через кадр (перелёт: 49
рывков на шести зумах против 0 у штатной анимации). CADisplayLink остался подстраховкой: шагает, только если карта не
начинала кадр дольше 25 мс.

Android: шаг от Choreographer, ровно раз в кадр (`MapMotionAnimator.vsyncDriven = true`); событие «карта начала кадр»
приходит в главный поток с опозданием, шаги от него шли неровно (90-я перцентиль 22–24 мс против 17,5 мс от такта).

## Сообщения о камере

Dart на главном потоке разбирает каждое сообщение, а в щипке и перелёте их шло по два-три на кадр. Поэтому «движется»
— не чаще 50 мс, а в перелёте и тихом режиме (`camera#quiet`) «начала» — одно в начале, «встала» и «карта встала»
(`mapOnIdle`) — одно в конце. `camera#follow` — камера за шторкой: цель ставится сразу, без ответа.
`camera#glide` с `fromCurrent: true` стартует от камеры в миг старта: быстрые нажатия подряд не прыгают.

## Картинки готовыми пикселями

`style#addImageRaw` — RGBA с предумноженной альфой, ширина, высота, плотность. iOS — `CGImage` из `CGDataProvider`
(`premultipliedLast | byteOrder32Big`), Android — `Bitmap.createBitmap(ARGB_8888)` + `copyPixelsFromBuffer`. PNG не
кодируется в Dart и не разбирается в нативе на главном потоке. `style#removeImages` — убрать картинки разом.

## Android

- `source#synchronous` до создания источника — `withSynchronousUpdate(true)` только для него. Ошибка движка
  maplibre-native#4326 (картинка, добавленная в кадре обновления синхронного источника, теряется) обходится в
  приложении: картинки меток не добавляются, пока идёт ход.
- Поверхность карты просит 120 Гц (`requestHighFrameRate` в `onMapReady`, `Surface.setFrameRate`): без просьбы система
  держала её на 60. С выключенным «Плавным экраном» просьба не действует.
- `MapMotionAnimator.java` собирает кадр хода строкой GeoJSON: неподвижные метки сериализуются один раз за ход,
  едущие дописываются.
- `map#gestureZoomRate` — сила зума жестами; приложение не пользуется (1,5 ломало двойное нажатие с протяжкой).

## Замер кадров карты

`bench#frames` — промежутки между отрисованными кадрами карты, только в движении (перелёт, ход, камера двигалась за
100 мс). iOS ещё время перекладки источника меток и добавления картинок, Android — `steps`, промежутки между шагами
хода. Пока замер не начат, ничего не копится. Пользуется `integration_test/frames_test.dart` приложения.

## Обновление на новую версию апстрима

Sync fork на GitHub (или `git fetch upstream` и `git push origin upstream/main:main`), затем:

```sh
git checkout patched
git rebase main   # наш коммит patched — поверх нового main
git push --force-with-lease origin patched
```

Конфликты решать по таблице «Что добавлено». Затем проверить движок и приложение на обоих телефонах, поставить тег
`v<новая версия>-patched.1` и поднять `ref` в `pubspec.yaml` движка.
