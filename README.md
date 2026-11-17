# FoodHub: app Android đặt đồ ăn (Capstone Design Quality Improvement)

Kotlin. Lõi xử lý đơn hàng là module JVM thuần, dữ liệu in-memory, UI Android viết bằng Jetpack Compose. Cài đặt đủ 7 design challenge: **C1 State, C2 Observer, C3 Abstract Factory (+ Factory Method), C4 Template Method, C5 Bridge, C6 Memento, C7 Facade**.

## Module

| Module | Loại | Nội dung |
|---|---|---|
| `:core` | Kotlin/JVM, **không** có Android plugin | `domain`, `data`, `presentation` (ViewModel + StateFlow), `app.AppContainer` (composition root), demo console `MainKt`, toàn bộ unit test |
| `:app` | Android application, Jetpack Compose | `FoodHubApplication` (giữ `AppContainer`), `MainActivity`, 2 màn hình Compose, lớp bọc ViewModel cho lifecycle Android |

Vì `:core` không có Android plugin nên domain không thể import `android.*`, và test chạy được mà không cần emulator.

## Cách chạy

Cần JDK 17 trở lên (Android Studio dùng sẵn JetBrains Runtime 21).

```
./gradlew :core:test              # 124 unit test và báo cáo JaCoCo (core/build/reports/jacoco/test/html/index.html)
./gradlew :core:run               # demo console, không cần Android
./gradlew :app:assembleDebug      # build APK
./gradlew :app:installDebug       # cài lên emulator/thiết bị (minSdk 33)
```

Hoặc mở thư mục gốc `Foodhub-release/` bằng Android Studio rồi Run cấu hình `app`.

## Màn hình

1. **Đặt món** (`ui/ordering/OrderingScreen.kt`): chọn nhà hàng VN/US, thêm món, đổi số lượng, hoàn tác, khôi phục nháp (Memento); chọn loại đơn (giao tận nơi, tự lấy, hẹn giờ), phương thức thanh toán, mã khuyến mãi; đặt món qua `CheckoutViewModel` → `OrderFacade` (Facade + Template Method + Abstract Factory). Mã người trả `DECLINED` giả lập thanh toán bị từ chối.
2. **Theo dõi đơn** (`ui/tracking/OrderTrackingScreen.kt`): trạng thái cập nhật realtime qua Observer; nút hủy, sửa món, hoàn tiền, đánh giá chỉ hiện khi State của đơn cho phép; nút giả lập nhà hàng, shipper, admin (xác nhận, chuẩn bị, giao, đã giao, gửi khuyến mãi); xuất hóa đơn HTML/CSV theo khu vực; danh sách thông báo đã gửi qua push/SMS/email (Bridge).

## Cấu trúc package (`core/src/main/kotlin/com/nemis/foodhub`)

| Package | Nội dung |
|---|---|
| `domain.model` | `Money`, `Currency`, `OrderId`, `OrderType`, `OrderStatus`, `Customer`, `MenuItem`, `CartItem` |
| `domain.order` | **C1** `OrderState` và 6 state; `Order` (context); **C2** `OrderEventPublisher`, `OrderStatusListener`, `SynchronousOrderEventPublisher` |
| `domain.region` (+ `.vn`, `.us`) | **C3** `RegionFactory`, `VietnamRegionFactory`, `UsRegionFactory`, `RegionRegistry` |
| `domain.payment`, `domain.pricing`, `domain.invoice` | `Payable`/`Refundable`, `PricingService`, `Invoice` và các formatter |
| `domain.checkout` | **C4** `CheckoutProcess`, `DeliveryCheckout`, `PickupCheckout`, `ScheduledCheckout` |
| `domain.facade`, `domain.usecase` | **C7** `OrderFacade`, `DefaultOrderFacade`; use case hủy, hoàn tiền, sửa món, xuất hóa đơn |
| `domain.notification` | **C5** `Notification` × `NotificationChannel`, `MultiChannelNotifier`, `PromoAnnouncer` |
| `domain.cart` | **C6** `Cart`, `CartSnapshot`, `CartHistory`, `CartEditor` |
| `domain.repository` | Interface repository (đặt ở domain, DIP) |
| `data` | Triển khai in-memory, kênh push/SMS/email giả lập |
| `presentation` | `MenuViewModel`, `CartViewModel`, `CheckoutViewModel`, `OrderTrackingViewModel`, `InvoiceViewModel` |
| `app` | `AppContainer` (nơi inject duy nhất), `Main.kt` (demo console) |

Chiều phụ thuộc: **View (`:app`) → Presentation → Domain ← Data**. `ArchitectureTest` kiểm tra bằng cách quét import.

## Tài liệu

- `docs/uml/*.puml` và `docs/uml/img/*.png`: 15 sơ đồ UML. Render lại bằng `plantuml -tpng -o img docs/uml/*.puml`.
- `docs/DESIGN_RATIONALE.md` (và `.docx`): Vấn đề → Lựa chọn → Đánh đổi, bằng chứng SOLID, MVVM, hạn chế đã biết, Change Request.

## Quyết định cho các điểm mơ hồ của đề

Pickup đi thẳng PREPARING → DELIVERED; thanh toán diễn ra ở checkout (đơn tạo ở trạng thái CREATED); COD chỉ thu tiền khi giao; đơn hẹn giờ dùng chung state machine (giờ hẹn cách hiện tại từ 30 phút đến 7 ngày). Chi tiết ở mục 1 của Design Rationale.
