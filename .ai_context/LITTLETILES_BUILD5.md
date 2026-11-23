# LittleTiles: сборка 5 (продолжение LITTLETILES_BUILD4.md)

## Что показала проверка сборки 4
- Зелёные контуры у всех наклонных плиток: данные плитки доходят до клиента, положение и наклон верные, видны и пропуски на подъёме.
- Сама разметка внутри контура не видна; на ямке (ручная разметка, подогнанная под верх миниблока) тоже пропала визуально.
- Клиент: PojavLauncher, GL через gl4es (Adreno), без Embeddium/Sodium. Предупреждение `ptm2:blockstates/sloped_marking.json missing model` ожидаемо (у блока нет модели).

## Причина невидимости (найдена по декомпиляции)
Линии разметки ptm2 (`center_line_marking`, `half_line_marking`, `double_line_marking`, `half_double_line_marking`, `platform_edge_line_marking`, `text_marking`) — блоки-сущности: модель блока пустая (в `models/block/center_line_marking_dry.json` только `particle`), линию рисует их рендерер через `MarkingLineConnector.connect(id, level, pos, state, ps, buffer, light)` -> `TextureRenderer.renderMarkingTexture`. Классы `RoadMarkingLine` и т.п. в декомпиляции отсутствуют (не декомпилировались). Стрелки, стоп-линия, зигзаг, треугольники и др. — обычные блоки с моделями (`RoadMarkingBig/Horizontal/ZigZag`). `renderSingleBlock` для линий рисует пустоту.

## Сделано в сборке 5 (в игре не проверено)
- `SlopedMarkingBlockEntity.mimicEntity()`: если исходный блок разметки — `EntityBlock`, на клиенте создаётся немирова блок-сущность того же типа (`newBlockEntity(pos, state)`, `setLevel`), кэшируется по ключу «id:rot».
- `SlopedMarkingRenderer`: для `EntityBlock` вызывается `BlockEntityRenderDispatcher.renderItem(inner, ps, buffer, light, overlay)` (родной рендерер линии), для остальных — `renderSingleBlock`. Плитка растягивается вдоль направления в 1/cos(pitch) (поворот оси z на направление -> scale -> обратно), чтобы горизонтальная проекция полностью заполняла ячейку, без зазоров между наклонными плитками.
- Отладочный контур (зелёный/красный) при карандаше в руке остаётся.

## Что проверить
- Видна ли теперь сплошная/двойная/пунктирная линия на pillar и на ямке; стыкуются ли плитки без зазоров; нет ли мигания/наложений с родной линией на соседних обычных блоках (соседи читаются как обычные блоки, соединения с наклонными плитками линия сама не рисует).
- Пропуски на подъёме: итоговое сообщение «Линия: обычных N, наклонных M, пропущено K» (сколько пропущено и где).
