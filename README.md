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
- `docs/DESIGN_RATIONALE.md` (và `.docx`, 6 trang, bản nộp D4): Vấn đề → Lựa chọn → Đánh đổi, bằng chứng SOLID, MVVM, hạn chế đã biết, Change Request.
- `docs/DESIGN_RATIONALE_full.md`: bản mở rộng dùng tham khảo khi bảo vệ (truy vết yêu cầu, bảng quyết định cho điểm mơ hồ, Phụ lục UML).
- `docs/tools/build-docx.sh`: tạo lại `docs/DESIGN_RATIONALE.docx` từ bản `.md` (cần pandoc ≥ 3.1): `sh docs/tools/build-docx.sh`.

## Quyết định cho các điểm mơ hồ của đề

Pickup đi thẳng PREPARING → DELIVERED; thanh toán diễn ra ở checkout (đơn tạo ở trạng thái CREATED); COD chỉ thu tiền khi giao; đơn hẹn giờ dùng chung state machine (giờ hẹn cách hiện tại từ 30 phút đến 7 ngày); hủy đơn trả hàng về kho, sửa món đổi tồn kho; đặt hàng xong thì xóa giỏ và nháp. Chi tiết ở mục 1.2 của `docs/DESIGN_RATIONALE_full.md`.

## Phân công công việc

### Tổng quan

| Thành viên | Tỉ lệ | Vai trò chính |
|---|---|---|
| **HungDQ58** | ~53% (10/19 commit) | Khởi tạo dự án, lõi domain, pattern C1–C6 và unit test của từng pattern |
| **TuanTA30** | ~21% (4/19 commit) | C7 Facade và use case, tầng Presentation (MVVM), composition root, UI Android |
| **KhoiPD9** | ~26% (5/19 commit) | Bộ UML, Design Rationale, README, rà soát đề và sửa cho khớp yêu cầu |

Số test ghi trong các bảng dưới là số test hiện tại của từng file (tổng 144 test).

### HungDQ58: lõi domain và các pattern C1–C6

| # | Hạng mục | Nội dung | File / lớp chính | Test | Commit |
|---|---|---|---|---|---|
| 1 | Khởi tạo dự án | Gradle đa module: `:core` là Kotlin/JVM thuần (không có Android plugin), `:app` là Android + Compose; version catalog; plugin `application` để chạy demo console; JaCoCo đo coverage | `settings.gradle.kts`, `build.gradle.kts`, `core/build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml` | - | `c5f72b6` |
| 2 | Model domain | Value object `Money` và `Currency` (cộng trừ cùng tiền tệ, làm tròn); `MenuItem`, `CartItem`, `Address`, `Customer`, `OrderType`, `OrderStatus`, `StatusTransition`; cây exception của domain (`DomainException`, `IllegalOrderOperationException`, `RefundWindowExpiredException`, `OrderNotFoundException`, `ValidationException`, `PaymentFailedException`, `OutOfStockException`, `UnsupportedRegionException`) | `domain/model/Models.kt`, `domain/model/Money.kt`, `domain/DomainExceptions.kt` | - | `dceec37` |
| 3 | Pricing, thanh toán, hóa đơn | `PricingService`, `PromoCode`, `PromoCatalog`, `TaxCalculator`, `PriceBreakdown`; `PaymentMethod` (thẻ, ví, COD), interface tách nhỏ `Payable` / `Refundable` (ISP), `PaymentProcessor`, `SimulatedPaymentProcessor`, `SimulatedRefundableProcessor`; `Invoice`, `InvoiceFormatter`, `BaseInvoiceFormatter` (HTML, CSV) | `domain/pricing/Pricing.kt`, `domain/payment/Payment.kt`, `domain/invoice/Invoice.kt` | `MoneyPricingInvoiceTest` (11), `TestSupport` (fixture dùng chung) | `7041beb` |
| 4 | **C1 State**: vòng đời đơn | `OrderState` và 6 state `CreatedState`, `ConfirmedState`, `PreparingState`, `DeliveringState`, `DeliveredState`, `CancelledState`; `Order` là context; `OrderCapabilities` (state cho phép sửa/hủy/hoàn tiền/đánh giá); `CancellationResult` (hủy miễn phí ở CREATED, phí 10% ở CONFIRMED); hoàn tiền trong 24 giờ; chuyển trạng thái sai ném `IllegalOrderOperationException` | `domain/order/OrderState.kt`, `domain/order/Order.kt` | `OrderStateTest` (18) | `5fcd7ef` |
| 5 | **C2 Observer**: theo dõi đơn | `OrderEventPublisher`, `OrderStatusListener`, `SynchronousOrderEventPublisher` (đăng ký/hủy đăng ký an toàn luồng), sự kiện `OrderStatusChanged`, listener analytics `OrderStatusCounter` | `domain/order/OrderEvents.kt` | `OrderObserverTest` (9) | `4ccd4a0` |
| 6 | **C3 Abstract Factory** (+ Factory Method): thanh toán đa khu vực | `RegionFactory` tạo bộ `PaymentProcessor` + `TaxCalculator` + `InvoiceFormatter`; `VietnamRegionFactory` (VND, VAT, `VietnamInvoiceFormatter`), `UsRegionFactory` (USD, sales tax, `UsInvoiceFormatter`); `RegionRegistry` tra factory theo khu vực | `domain/region/Region.kt`, `domain/region/vn/VietnamRegion.kt`, `domain/region/us/UsRegion.kt` | `RegionFactoryTest` (11) | `dc6b19b` |
| 7 | Repository (DIP) | Interface đặt ở domain: `OrderRepository`, `MenuRepository`, `InventoryService`, `IdGenerator`; bản in-memory ở data: `InMemoryOrderRepository`, `InMemoryMenuRepository`, `SimulatedInventoryService`, `InMemoryCartDraftRepository`, `InMemoryPromoCatalog`, `SequentialIdGenerator` | `domain/repository/Repositories.kt`, `data/InMemoryRepositories.kt` | - | `552b8e1` |
| 8 | **C4 Template Method**: quy trình checkout | `CheckoutProcess` cố định thứ tự validate → tính giá → thanh toán → xác nhận → thông báo; 3 lớp con `DeliveryCheckout`, `PickupCheckout`, `ScheduledCheckout` (giờ hẹn từ 30 phút đến 7 ngày) chỉ override các bước khác nhau; `CheckoutRequest`, `CheckoutContext`, `CheckoutServices`, `OrderConfirmation` | `domain/checkout/CheckoutProcess.kt`, `domain/checkout/CheckoutFlows.kt` | `CheckoutTemplateTest` (18) | `4b9c183` |
| 9 | **C5 Bridge**: thông báo đa kênh | Nhánh loại thông báo `Notification` (`OrderUpdateNotification`, `PromoNotification`, `UrgentNotification`) tách khỏi nhánh kênh `NotificationChannel`; `MultiChannelNotifier`, `CustomerNotifier`, `NotificationOrderListener` (nối với Observer), `PromoAnnouncer`; kênh giả lập `PushChannel`, `SmsChannel`, `EmailChannel`, `SentMessageLog` | `domain/notification/Notification.kt`, `data/channel/Channels.kt` | `NotificationBridgeTest` (7) | `e6b426e` |
| 10 | **C6 Memento**: nháp và undo giỏ hàng | `Cart` (originator), `CartSnapshot` (memento, không lộ state bên trong), `CartHistory` (nhiều bản backup, có giới hạn), `CartEditor`, interface `CartDraftRepository` | `domain/cart/Cart.kt`, `domain/cart/CartEditor.kt` | `CartMementoTest` (20) | `67907bc` |

### TuanTA30: C7 Facade, tầng Presentation và UI

| # | Hạng mục | Nội dung | File / lớp chính | Test | Commit |
|---|---|---|---|---|---|
| 1 | **C7 Facade** và use case | `OrderFacade` là API mức cao duy nhất ViewModel gọi để checkout; `DefaultOrderFacade` chỉ điều phối, không chứa logic nghiệp vụ; `OrderSummary`; use case `CancelOrderUseCase`, `RefundOrderUseCase`, `EditOrderItemsUseCase`, `ExportInvoiceUseCase`, `OrderLifecycleService`, `RefundExecutor` | `domain/facade/OrderFacade.kt`, `domain/usecase/OrderUseCases.kt` | `OrderFacadeTest` (13) | `80d4a7e` |
| 2 | Tầng Presentation (MVVM) | `BaseViewModel`; `MenuViewModel`, `CartViewModel`, `CheckoutViewModel`, `OrderTrackingViewModel`, `InvoiceViewModel` cùng các UI state; state expose qua `StateFlow`, ViewModel không giữ View/Context | `presentation/ViewModels.kt` | `ViewModelTest` (18) | `010fdb4` |
| 3 | Composition root và demo console | `AppContainer` là nơi duy nhất khởi tạo và inject dependency; `Main.kt` chạy toàn bộ luồng đặt hàng bằng console; test kiến trúc quét import để giữ đúng chiều phụ thuộc; test luồng khách hàng từ đầu đến cuối | `app/AppContainer.kt`, `app/Main.kt` | `ArchitectureTest` (9), `CustomerFlowTest` (10) | `0a8d8dd` |
| 4 | UI Android (Jetpack Compose) | `FoodHubApplication` giữ `AppContainer`; `MainActivity`; lớp bọc ViewModel theo lifecycle Android (`LifecycleBridges.kt`); màn **Đặt món** (`OrderingScreen`) và **Theo dõi đơn** (`OrderTrackingScreen`) chỉ hiển thị và chuyển sự kiện; theme | `app/src/main/java/com/nemis/foodhub/` | - | `e18dfe0` |

### KhoiPD9: UML, tài liệu, rà soát yêu cầu

| # | Hạng mục | Nội dung | File / lớp chính | Test | Commit |
|---|---|---|---|---|---|
| 1 | **D1** bộ UML (PlantUML) | Use case diagram; class diagram lõi, class diagram cho từng pattern (State, Observer, Abstract Factory, Template Method, Facade, Bridge, Memento), phân lớp MVVM, kiểu hỗ trợ; sequence diagram đặt hàng, đổi trạng thái → thông báo observer, hủy đơn có phí; state diagram (bonus); xuất ảnh PNG | `docs/uml/01..15-*.puml`, `docs/uml/img/` | - | `ffa9966` |
| 2 | **D4** Design Rationale và README | Bản đầu của rationale (Vấn đề → Lựa chọn → Đánh đổi cho từng pattern, bằng chứng SOLID, MVVM, hạn chế); README (cách chạy, cấu trúc package) | `docs/DESIGN_RATIONALE.md`, `docs/DESIGN_RATIONALE.docx`, `README.md` | - | `9f1d9e2` |
| 3 | Cấu hình repo | Bỏ qua thư mục build của mọi module trong `.gitignore` | `.gitignore` | - | `e928d44` |
| 4 | Rà soát đề và sửa cho khớp yêu cầu | Lưu nháp giỏ hàng ra file để khôi phục khi app bị kill (`FileCartDraftRepository`, `CartSnapshotCodec`); tách `Subscription` (handle hủy đăng ký) ra file riêng để dùng chung; `OrderView` đọc trạng thái đơn nguyên tử; outbox thông báo đã gửi (`NotificationOutbox`, `SentNotification`); ViewModel mới `SimulatorViewModel` (giả lập nhà hàng/shipper/admin), `NotificationLogViewModel`, `CheckoutForm`; cập nhật 2 màn hình Compose; bổ sung test cho Memento, Template Method, Facade, ViewModel, kiến trúc | `data/FileCartDraftRepository.kt`, `domain/Subscription.kt`, `domain/cart/`, `domain/notification/`, `presentation/ViewModels.kt`, `app/src/main/...` | +434 dòng test trên 8 file test | `c25452e` |
| 5 | Cập nhật tài liệu khớp code | Viết lại Design Rationale đầy đủ (quyết định cho điểm mơ hồ, phương án đã loại bỏ, Change Request); script `build-docx.sh` tạo `.docx` từ `.md` bằng pandoc; cập nhật 11 sơ đồ UML và README theo code sau review | `docs/DESIGN_RATIONALE.*`, `docs/tools/`, `docs/uml/` | - | `349d033` |
