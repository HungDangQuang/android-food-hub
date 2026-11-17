# FoodHub: app Android đặt đồ ăn (Capstone Design Quality Improvement)

Kotlin. Lõi xử lý đơn hàng là module JVM thuần, dữ liệu in-memory, UI Android viết bằng Jetpack Compose. Cài đặt đủ 7 design challenge: **C1 State, C2 Observer, C3 Abstract Factory (+ Factory Method), C4 Template Method, C5 Bridge, C6 Memento, C7 Facade**.

## Module

| Module | Loại | Nội dung |
|---|---|---|
| `:core` | Kotlin/JVM, **không** có Android plugin | `domain`, `data`, `presentation` (ViewModel + StateFlow), `app.AppContainer` (composition root), demo console `MainKt`, toàn bộ unit test |
| `:app` | Android application, Jetpack Compose | `FoodHubApplication` (giữ `AppContainer`, chỉ định thư mục lưu nháp), `MainActivity`, 2 màn hình Compose chỉ nói chuyện với ViewModel, lớp bọc ViewModel cho lifecycle Android |

Vì `:core` không có Android plugin nên domain không thể import `android.*`, và test chạy được mà không cần emulator.

## Cách chạy

Cần JDK 17 trở lên (Android Studio dùng sẵn JetBrains Runtime 21).

```
./gradlew :core:test              # 144 unit test và báo cáo JaCoCo (core/build/reports/jacoco/test/html/index.html)
./gradlew :core:run               # demo console, không cần Android
./gradlew :app:assembleDebug      # build APK
./gradlew :app:installDebug       # cài lên emulator/thiết bị (minSdk 33)
```

Hoặc mở thư mục gốc `Foodhub-release/` bằng Android Studio rồi Run cấu hình `app`.

## Màn hình

1. **Đặt món** (`ui/ordering/OrderingScreen.kt`): chọn nhà hàng VN/US, thêm món, đổi số lượng, hoàn tác, khôi phục nháp (Memento; nháp được ghi ra file nên còn sau khi app bị kill, nút chỉ bật khi giỏ trống và có nháp); chọn loại đơn (giao tận nơi, tự lấy, hẹn giờ), phương thức thanh toán, mã khuyến mãi; màn hình gửi một `CheckoutForm`, `CheckoutViewModel` dựng request rồi gọi `OrderFacade` (Facade + Template Method + Abstract Factory). Đặt thành công thì giỏ và nháp được xóa. Mã người trả `DECLINED` giả lập thanh toán bị từ chối.
2. **Theo dõi đơn** (`ui/tracking/OrderTrackingScreen.kt`): trạng thái cập nhật realtime qua Observer; danh sách món với nút −/+ khi State cho phép sửa (tồn kho đổi theo); nút hủy, hoàn tiền, đánh giá chỉ hiện khi State của đơn cho phép, kết quả hiện kèm số tiền (phí hủy, số tiền hoàn); nút giả lập nhà hàng, shipper, admin qua `SimulatorViewModel` (xác nhận, chuẩn bị, giao, đã giao, gửi khuyến mãi); xuất hóa đơn HTML/CSV theo khu vực; danh sách thông báo đã gửi qua push/SMS/email (Bridge) qua `NotificationLogViewModel`.

## Cấu trúc package (`core/src/main/kotlin/com/nemis/foodhub`)

| Package | Nội dung |
|---|---|
| `domain` | `Subscription` (handle hủy đăng ký dùng chung), các exception của domain |
| `domain.model` | `Money`, `Currency`, `OrderId`, `OrderType`, `OrderStatus`, `Customer`, `MenuItem`, `CartItem` |
| `domain.order` | **C1** `OrderState` và 6 state; `Order` (context, `view()` đọc nguyên tử); **C2** `OrderEventPublisher`, `OrderStatusListener`, `SynchronousOrderEventPublisher`, `OrderStatusChanged` (có `version`) |
| `domain.region` (+ `.vn`, `.us`) | **C3** `RegionFactory`, `VietnamRegionFactory`, `UsRegionFactory`, `RegionRegistry` |
| `domain.payment`, `domain.pricing`, `domain.invoice` | `Payable`/`Refundable`, `PricingService`, `Invoice` và các formatter |
| `domain.checkout` | **C4** `CheckoutProcess`, `DeliveryCheckout`, `PickupCheckout`, `ScheduledCheckout` |
| `domain.facade`, `domain.usecase` | **C7** `OrderFacade`, `DefaultOrderFacade`; use case hủy, hoàn tiền, sửa món, xuất hóa đơn |
| `domain.notification` | **C5** `Notification` × `NotificationChannel`, `MultiChannelNotifier`, `PromoAnnouncer`; `NotificationOutbox` (đọc tin đã gửi) |
| `domain.cart` | **C6** `Cart` (+ `SnapshotCodec`), `CartSnapshot`, `CartHistory`, `CartEditor`, `CartDraftRepository` |
| `domain.repository` | Interface repository (đặt ở domain, DIP) |
| `data` | Triển khai in-memory, `FileCartDraftRepository` (nháp ra file), kênh push/SMS/email giả lập, `SentMessageLog` |
| `presentation` | `MenuViewModel`, `CartViewModel`, `CheckoutViewModel`, `OrderTrackingViewModel`, `InvoiceViewModel`, `SimulatorViewModel`, `NotificationLogViewModel` |
| `app` | `AppContainer` (nơi inject duy nhất), `Main.kt` (demo console) |

Chiều phụ thuộc: **View (`:app`) → Presentation → Domain ← Data**. `ArchitectureTest` kiểm tra bằng cách quét import, kể cả các màn hình của `:app` (không được import `app`, `data`, use case, facade… và không tự bắt lỗi domain).

## Tài liệu

- `docs/uml/*.puml` và `docs/uml/img/*.png`: 15 sơ đồ UML. Render lại bằng `plantuml -tpng -o img docs/uml/*.puml`.
- `docs/DESIGN_RATIONALE.md` (và `.docx`): Vấn đề → Lựa chọn → Đánh đổi, bằng chứng SOLID, MVVM, hạn chế đã biết, Change Request.
- `docs/tools/build-docx.sh`: tạo lại `docs/DESIGN_RATIONALE.docx` từ bản `.md` (cần pandoc ≥ 3.1): `sh docs/tools/build-docx.sh`.

## Quyết định cho các điểm mơ hồ của đề

Pickup đi thẳng PREPARING → DELIVERED; thanh toán diễn ra ở checkout (đơn tạo ở trạng thái CREATED); COD chỉ thu tiền khi giao; đơn hẹn giờ dùng chung state machine (giờ hẹn cách hiện tại từ 30 phút đến 7 ngày); hủy đơn trả hàng về kho, sửa món đổi tồn kho; đặt hàng xong thì xóa giỏ và nháp. Chi tiết ở mục 1 của Design Rationale.

## Phân công công việc

| Thành viên | Tỉ lệ | Phạm vi phụ trách | Commit |
|---|---|---|---|
| **HungDQ58** | ~53% (10/19 commit) | Khởi tạo dự án Gradle đa module; model domain, `Money`, exception; pricing, payment (`Payable`/`Refundable`), invoice; **C1 State**, **C2 Observer**, **C3 Abstract Factory**, repository interface và bản in-memory, **C4 Template Method**, **C5 Bridge**, **C6 Memento**, cùng unit test của từng pattern | `c5f72b6`, `dceec37`, `7041beb`, `5fcd7ef`, `4ccd4a0`, `dc6b19b`, `552b8e1`, `4b9c183`, `e6b426e`, `67907bc` |
| **TuanTA30** | ~21% (4/19 commit) | **C7 Facade** và các use case hủy/hoàn tiền/sửa món/xuất hóa đơn; ViewModel MVVM với StateFlow; `AppContainer` (composition root) và demo console; `ArchitectureTest`, `CustomerFlowTest`; UI Jetpack Compose (màn Đặt món, Theo dõi đơn) | `80d4a7e`, `010fdb4`, `0a8d8dd`, `e18dfe0` |
| **KhoiPD9** | ~26% (5/19 commit) | Bộ UML (use case, class, sequence, state); Design Rationale và README; cấu hình `.gitignore`; rà soát đề và sửa cho khớp yêu cầu (lưu nháp ra file, outbox thông báo, bổ sung test); cập nhật rationale, README, UML khớp với code | `ffa9966`, `9f1d9e2`, `e928d44`, `c25452e`, `349d033` |
