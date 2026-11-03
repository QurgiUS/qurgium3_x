# Портирование

`core/` переносится без изменений (Java 17+, без JOML/Gson/MC). Адаптируется только `mc/`:

| # | Файл | Что зависит от версии |
|---|------|------------------------|
| 1 | `mixin/LivingEntityRendererMixin` | 26.x/1.21.9+: `extractRenderState` + `submit(state, PoseStack, SubmitNodeCollector, CameraRenderState)`. 1.21.2–1.21.8: `render(state, PoseStack, MultiBufferSource, int)`. ≤1.21.1: нет render-state, `render(entity, yaw, pt, …)`, триггер прямо по entity, duck-интерфейс не нужен |
| 2 | `ModelCapture` | `ModelPart.Vertex` (record x,y,z,u,v ↔ старый `pos`+`u`,`v`), `Polygon.normal`, `storePose/loadPose` (≤1.20 — вручную копировать x/y/z/xRot/yRot/zRot) |
| 3 | `McWorldCollider` | `VoxelShape.toAabbs`, `hasChunkAt` — стабильны с 1.16 |
| 4 | `render/RagdollRenderer` | `VertexConsumer.addVertex/setColor/...` (1.21+) ↔ `vertex().color().uv().overlayCoords().uv2().normal().endVertex()` (≤1.20.6); `RenderType` пакет (26.x: `rendertype`) ; JOML ↔ `com.mojang.math` (≤1.19.2) |
| 5 | `render/RenderHook` | имя/пакет `WorldRenderEvents` в Fabric API, способ получить матрицу вида |

Также: `Camera.position()` ↔ `getPosition()`, `CycleButton.builder` сигнатура, цвет текста ARGB (1.21.6+) ↔ RGB, mappings: 26.x без маппингов, старые версии — `loom.officialMojangMappings()`.
