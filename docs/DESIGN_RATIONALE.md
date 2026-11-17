# FoodHub: Design Rationale

**Capstone Project – Design Quality Improvement (Intermediate).** Lõi xử lý đơn hàng cho ứng dụng Customer của FoodHub.

| Thông tin | Nội dung |
|---|---|
| Thành viên | Hung Dang Quang (tác giả commit trong repo) · *(bổ sung các thành viên khác nếu có)* |
| Phân công | *(bổ sung: ai phụ trách pattern / phần nào)* |
| Công nghệ | Kotlin 2.2.10, Gradle 9.4.1, JDK 17+; module `:core` (Kotlin/JVM thuần) và `:app` (Android, Jetpack Compose) |
| Phạm vi | C1, C2, C3, C4, C7 (bắt buộc) và **cả C5 lẫn C6** (bonus); state diagram (bonus) |
| Kiểm thử | 144 unit test, tất cả xanh; line coverage domain 97,7% (736/753 dòng, JaCoCo) |

**Quy ước.** Đường dẫn tính từ `core/src/main/kotlin/com/nemis/foodhub/domain/` (ví dụ `order/OrderState.kt:56`); file Android tính từ `app/src/main/java/com/nemis/foodhub/`. "Sơ đồ 03" là `docs/uml/03-*.puml` trong bộ UML (D1).

## 1. Kiến trúc MVVM

| Lớp | Module / package | Thành phần chính | Phụ thuộc vào |
|---|---|---|---|
| View | `:app` · `ui.ordering`, `ui.tracking` | `OrderingScreen`, `OrderTrackingScreen` (Compose) | ViewModel |
| ViewModel | `:core` · `presentation` | `MenuViewModel`, `CartViewModel`, `CheckoutViewModel`, `OrderTrackingViewModel`, `InvoiceViewModel`, `SimulatorViewModel`, `NotificationLogViewModel` | Domain |
| Domain | `:core` · `domain.*` | entity, state, factory, checkout, facade, use case, **interface repository** | Chỉ JDK |
| Data | `:core` · `data` | `InMemoryOrderRepository`, `FileCartDraftRepository`, `PushChannel`/`SmsChannel`/`EmailChannel`… | Domain |
| Composition root | `:core` · `app.AppContainer` | Nơi duy nhất tạo lớp cụ thể (constructor injection) | Tất cả |

Chiều phụ thuộc là **Presentation → Domain ← Data** (sơ đồ 10). `:core` không có Android plugin, nên domain *không thể* import `android.*` và toàn bộ test chạy không cần emulator. Các ràng buộc của mục 5 đề được kiểm tra bằng test kiến trúc:

- **View chỉ hiển thị và chuyển sự kiện:** màn hình chỉ `collectAsStateWithLifecycle()` và gọi ViewModel; form gửi đi dạng `CheckoutForm` thô, `CheckoutViewModel` mới dựng `CheckoutRequest` (`ArchitectureTest.screensTalkOnlyToViewModels`, `businessRulesAreNotInThePresentationLayer`).
- **Domain thuần JVM, Data ở ngoài cùng, interface repository ở domain:** `OrderRepository`, `MenuRepository`, `InventoryService`, `CartDraftRepository`, `NotificationOutbox` đều khai báo trong domain (`ArchitectureTest.domainIsPureJvm`, `domainDoesNotDependOnOuterLayers`, `dataLayerDoesNotKnowPresentation`).
- **ViewModel không giữ View/Context:** constructor chỉ nhận abstraction của domain, expose `StateFlow` của `UiState` bất biến (`ViewModelTest.viewModelsDependOnlyOnDomainTypes`).
- **Luồng một chiều:** nút → `OrderTrackingViewModel.cancel()` → `OrderFacade.cancelOrder` → `Order.cancel()` → state mới → event → `StateFlow` → View vẽ lại (sơ đồ 12, 14).

Trên Android, `OrderingViewModels` và `OrderScreenViewModels` (`ui/LifecycleBridges.kt`) kế thừa `androidx.lifecycle.ViewModel` chỉ để bọc các ViewModel thuần JVM: giữ chúng qua xoay màn hình và chuyển `onCleared()` xuống để hủy subscription Observer. Các lớp bọc không chứa state hay quy tắc nghiệp vụ.

## 2. Design patterns

Mỗi pattern theo khung: Vấn đề → Lựa chọn → Đánh đổi → Phương án đã loại bỏ, kèm test chứng minh và sơ đồ D1.

### 2.1 C1. State: vòng đời đơn hàng

**Vấn đề.** Sửa món, hủy, hoàn tiền thay đổi theo trạng thái; chuyển sai phải bị từ chối rõ ràng; không dùng chuỗi `if/when` trên enum.

**Lựa chọn.** `OrderState` (`order/OrderState.kt:56`) **từ chối mọi thao tác theo mặc định** bằng `IllegalOrderOperationException`; sáu state con chỉ override đúng thao tác được phép. `Order` (context) ủy quyền mọi hành vi cho state hiện tại; state chỉ thấy giao diện hẹp `OrderContext`. Mỗi state công bố `OrderCapabilities`, nên ViewModel hiện nút theo dữ liệu domain thay vì lặp lại quy tắc.

| Trạng thái | Sửa món | Hủy đơn | Chuyển tiếp hợp lệ / ghi chú |
|---|---|---|---|
| CREATED | Có | Miễn phí, hoàn 100% | → CONFIRMED, → CANCELLED |
| CONFIRMED | Không | Phí 10%, hoàn 90% | → PREPARING, → CANCELLED |
| PREPARING | Không | Không | → DELIVERING (giao, hẹn giờ); → DELIVERED (pickup) |
| DELIVERING | Không | Không | Xem vị trí courier; → DELIVERED |
| DELIVERED | Không | Không | Hoàn tiền trong 24h (một lần, chỉ đơn trả online); đánh giá một lần |
| CANCELLED | Không | Không | Trạng thái cuối |

**Đánh đổi.** Thêm 6 lớp và `OrderContext`; đổi lại thêm trạng thái chỉ cần một lớp và sửa state đứng trước. State là `object` không giữ dữ liệu; đây là `object` duy nhất được phép trong domain. Pickup không có DELIVERING: `PreparingState` rẽ nhánh theo `type.requiresCourier` (theo *loại đơn*, không theo trạng thái) để giữ đúng bảng trạng thái của đề.

**Phương án đã loại bỏ.** (1) Enum + `when` ở mọi nơi: đúng lỗi đề cảnh báo. (2) Bảng chuyển trạng thái dạng `Map<Status, Set<Status>>`: chỉ biết được chuyển hay không, không gắn được hành vi như phí hủy hay cửa sổ hoàn tiền.

**Bằng chứng.** `OrderStateTest.everyOperationOutsideTheAllowedSetIsRejectedWithoutSideEffects` (39 tổ hợp không hợp lệ đều bị từ chối, không đổi trạng thái, không phát event); `ArchitectureTest.noBranchingOnOrderStatusAnywhereInTheDomain` quét mã nguồn, không có `when/if` theo status. Sơ đồ 03, 13.

### 2.2 C2. Observer: theo dõi đơn thời gian thực

**Vấn đề.** UI, thông báo, analytics cùng phản ứng khi đơn đổi trạng thái; nguồn phát không biết ai nghe; xử lý đăng ký/hủy đăng ký và luồng.

**Lựa chọn.** `Order` chỉ giữ interface `OrderEventPublisher` và phát `OrderStatusChanged` **sau khi nhả lock** (`order/Order.kt:92-99`). Ba observer cụ thể: `OrderTrackingViewModel` (UI), `NotificationOrderListener` (thông báo), `OrderStatusCounter` (analytics). `subscribe()` trả `Subscription` để hủy; ViewModel hủy trong `onCleared()`. Về luồng: danh sách đăng ký là `CopyOnWriteArrayList`, listener lỗi bị cô lập, ViewModel ghi vào `StateFlow` và View thu thập trên main thread. ViewModel đăng ký **trước** rồi mới đọc đơn; event và kết quả đọc đều mang `version`, bản mới hơn được giữ, nên event đến đúng lúc mở màn hình không bị mất.

**Đánh đổi.** Listener chạy đồng bộ trên thread bên phát, nên listener chậm làm chậm bên phát (chấp nhận ở quy mô này). Trường `version` là cái giá của "đăng ký trước, đọc sau"; làm ngược lại đơn giản hơn nhưng có thể lỡ event.

**Phương án đã loại bỏ.** (1) Polling: chậm, tốn tài nguyên. (2) `LiveData` trong domain: domain phụ thuộc Android. (3) EventBus toàn cục: ẩn phụ thuộc, khó test.

**Bằng chứng.** `OrderObserverTest.uiNotificationAndAnalyticsAllReactToOneTransition`, `cancelledSubscriptionNoLongerReceivesEvents`, `publishingAndSubscribingFromManyThreadsLosesNothing`; `ViewModelTest.anEventThatArrivesWhileTheScreenOpensIsNotLost`. Sơ đồ 04, 12.

### 2.3 C3. Abstract Factory (+ Factory Method): thanh toán đa khu vực

**Vấn đề.** Mỗi khu vực cần bộ `PaymentProcessor`, `TaxCalculator`, `InvoiceFormatter` đi cùng nhau; thêm khu vực không sửa code cũ.

**Lựa chọn.** `RegionFactory` (`region/Region.kt:21`) với hai factory cụ thể `VietnamRegionFactory` (VND, VAT 10%, VNPay/MoMo/COD, hóa đơn tiếng Việt) và `UsRegionFactory` (USD, sales tax 7%, Stripe/ApplePay/COD, hóa đơn tiếng Anh). Mỗi factory tạo trọn bộ, nên không thể ghép VAT Việt Nam với hóa đơn Mỹ. `createPaymentProcessor(method)` là **Factory Method**: điểm vào cố định giao việc chọn processor cho hook `newPaymentProcessor`, rồi kiểm tra hợp đồng (đúng method, đúng currency).

**Đánh đổi.** Thêm một *loại sản phẩm* mới vào họ phải sửa mọi factory (nhược điểm kinh điển; chấp nhận vì tập sản phẩm ổn định). `PaymentMethod` cố ý là enum với `when` vét cạn trong mỗi factory: mỗi khu vực *bắt buộc* quyết định gateway cho phương thức mới và trình biên dịch báo nếu quên. Vì vậy bằng chứng OCP dùng kênh thông báo (mục 3).

**Phương án đã loại bỏ.** (1) Một factory với `when(region)`: vi phạm OCP. (2) Ba Strategy độc lập cho thuế, hóa đơn, thanh toán: cho phép ghép sai họ. (3) `ServiceLoader`: quá mức cần thiết, một dòng ở `AppContainer` là đủ.

**Bằng chứng.** `RegionFactoryTest.sameOrderIsPricedAndInvoicedDifferentlyPerRegion`, `factoryMethodRejectsAProcessorThatBreaksTheRegionContract`, và `aNewRegionWithItsOwnCurrencyWorksEndToEndWithoutChangingExistingCode`: `JapanRegionFactory` (JPY) định nghĩa hoàn toàn trong test, checkout và hủy đơn thành công qua code hiện có. Sơ đồ 05.

### 2.4 C4. Template Method: quy trình checkout

**Vấn đề.** Các bước cố định validate → tính giá → thanh toán → xác nhận → thông báo, nhưng mỗi loại đơn khác nhau ở một số bước.

**Lựa chọn.** `CheckoutProcess.checkout()` (`checkout/CheckoutProcess.kt:83-92`) cố định thứ tự và không override được (Kotlin mặc định `final`); năm bước chung là `private`. Ba lớp con `DeliveryCheckout`, `PickupCheckout`, `ScheduledCheckout` chỉ khác ở ba hook: `validateTypeRules` (giao: bắt buộc địa chỉ; pickup: không được hẹn giờ; hẹn giờ: giờ hẹn trong [+30 phút, +7 ngày]), `deliveryFee` (pickup = 0) và `placedMessage`. Checkout tạo đơn ở CREATED sau khi thanh toán thành công; nếu bước xác nhận lỗi thì trả kho và hoàn tiền.

**Đánh đổi.** Kế thừa làm lớp con gắn chặt lớp cha; chấp nhận vì chỉ có ba biến thể khác nhau rất ít. Lớp con không thể bỏ qua kiểm tra tồn kho hay đổi thứ tự, nhưng muốn thay một bước chung cho riêng một loại đơn thì phải thêm hook mới. Nếu số biến thể tăng hoặc cần ghép chéo, nên chuyển hook sang Strategy.

**Phương án đã loại bỏ.** (1) Một lớp checkout với `if (type == …)` ở từng bước. (2) Strategy cho từng bước: nhiều đối tượng nhỏ nhưng không gì ép thứ tự các bước.

**Bằng chứng.** `CheckoutTemplateTest.stepsRunInTheFixedOrderValidatePricePayConfirmNotify`, `neitherTheTemplateNorItsFixedStepsCanBeOverridden` (reflection), `noStepAfterAFailedStepIsExecuted`, `paymentIsRefundedWhenStockDisappearsBetweenValidationAndConfirmation`. Sơ đồ 06, 11.

### 2.5 C7. Facade: điểm vào đơn giản cho ViewModel

**Vấn đề.** ViewModel chỉ gọi một API mức cao, không biết Inventory, Pricing, Payment, Notification; Facade không được thành God class.

**Lựa chọn.** `OrderFacade` (`facade/OrderFacade.kt:42`) có 8 phương thức; `DefaultOrderFacade` **chỉ ủy quyền**: `checkout` → `CheckoutProcess` theo `OrderType`; `cancelOrder` / `requestRefund` / `editOrderItems` / `exportInvoice` → bốn use case; `track` → `OrderEventPublisher.subscribe`; `getOrder` / `rateOrder` → `OrderRepository` / `Order`.

**Đánh đổi.** Thêm một tầng gián tiếp; đổi lại ViewModel dễ test và thay đổi bên trong domain không lan ra UI. `OrderLifecycleService` và `PromoAnnouncer` (giả lập app Restaurant/Courier/Admin) cố ý nằm ngoài facade để facade không phình ra. Kho chỉ được chạm tới trong use case.

**Phương án đã loại bỏ.** (1) ViewModel gọi thẳng từng service: lộ phụ thuộc. (2) Một `CheckoutService` chứa mọi logic: chính là God class.

**Bằng chứng.** `OrderFacadeTest.facadeDoesNotKnowInventoryPricingPaymentOrNotification` (reflection trên trường và chữ ký), `facadeApiStaysSmall` (tối đa 8 phương thức), `oneCallPlacesAnOrderEndToEnd`. Sơ đồ 07.

### 2.6 C5. Bridge: thông báo đa kênh (bonus)

**Vấn đề.** Loại thông báo (OrderUpdate, Promo, Urgent) và kênh (Push, SMS, Email) phát triển độc lập; tránh bùng nổ số lớp.

**Lựa chọn.** Hai cây phân cấp nối bằng một tham chiếu: `Notification` (nói *gì*) giữ `NotificationChannel` (gửi *thế nào*), trong `notification/Notification.kt`. Định dạng riêng nằm ở kênh: SMS bỏ tiêu đề và cắt còn 160 ký tự, email thêm tiền tố `[FoodHub]`. Quan hệ với C2: Observer quyết định *khi nào, ai* được báo; Bridge quyết định *qua kênh nào, định dạng gì*; hai bên nối qua `CustomerNotifier`.

| Phương án | Số lớp (3 loại × 3 kênh) | Thêm kênh thứ 4 | Thêm loại thứ 4 |
|---|---|---|---|
| Kế thừa theo tích (`PushPromo`, `SmsUrgent`…) | 9 | +3 lớp | +3 lớp |
| **Bridge (đã chọn)** | **6** (3 + 3) | **+1 lớp** | **+1 lớp** + 1 phương thức ở `CustomerNotifier` |

**Đánh đổi.** Thêm một tầng trừu tượng so với gọi thẳng kênh; chỉ có lợi khi cả số loại lẫn số kênh cùng tăng, đúng như đề nêu. **Phương án đã loại bỏ:** kế thừa theo tích (bảng trên); một lớp với `when(channel)` (vi phạm OCP).

**Bằng chứng.** `NotificationBridgeTest.everyKindOfNotificationCanTravelOverEveryChannel`, `channelsFormatTheSameMessageTheirOwnWay`, `aNewChannelIsOneNewClassAndNothingElseChanges`. Sơ đồ 08.

### 2.7 C6. Memento: nháp và undo giỏ hàng (bonus)

**Vấn đề.** Lưu/khôi phục snapshot giỏ hàng mà không phá encapsulation; nhiều bản backup, có giới hạn.

**Lựa chọn.** `Cart` (originator, `cart/Cart.kt:36`) tạo memento là lớp lồng **`private`**; bên ngoài chỉ thấy interface `CartSnapshot` với đúng thuộc tính `takenAt`. Caretaker: `CartHistory(capacity)` giữ tối đa 10 bản, đầy thì bỏ bản cũ nhất; `CartDraftRepository` giữ nháp. Để nháp sống sót khi process bị kill, chính `Cart.SnapshotCodec` mã hóa snapshot thành chuỗi; `FileCartDraftRepository` chỉ ghi chuỗi ra file (ghi file tạm rồi đổi tên) mà không hiểu nội dung. Đặt hàng thành công thì xóa giỏ, lịch sử undo và nháp.

**Đánh đổi.** Mỗi thay đổi sao chép map dòng hàng (rẻ vì `CartItem` bất biến) và ghi một file nhỏ. Snapshot giữ giá tại lúc lưu, nên menu đổi giá trong lúc app tắt thì nháp mang giá cũ. Chưa có redo.

**Phương án đã loại bỏ.** (1) Lộ các trường của `Cart` để sao chép từ ngoài: phá encapsulation. (2) Command với lệnh đảo: phức tạp hơn nhiều cho cùng kết quả. (3) Serialize bằng thư viện JSON ở tầng data: tầng data phải đọc được cấu trúc bên trong memento.

**Bằng chứng.** `CartMementoTest.theCaretakerSeesOnlyATimestampAndCannotForgeSnapshots`, `onlyTheNewestNBackupsAreKept`, `aDraftWrittenToDiskSurvivesTheProcess`, `completingCheckoutClearsCartHistoryAndDraft`. Sơ đồ 09.

### 2.8 Những chỗ cố ý không dùng pattern

`PricingService` không dùng Strategy (chỉ có một công thức; thuế khác nhau đã do `TaxCalculator` lo). `RefundExecutor` không dùng Strategy (khác biệt giữa các phương thức đã nằm ở `Refundable`). Không có Singleton: mọi phụ thuộc đi qua constructor từ `AppContainer` (`ArchitectureTest.noSingletonsInTheDomainExceptTheStatelessOrderStates`). `CheckoutServices` chỉ là parameter object, không cần Builder hay Mediator.

## 3. Bằng chứng SOLID

**SRP.** Các lớp đã tách trách nhiệm, mỗi lớp một lý do thay đổi:

| Lớp | Trách nhiệm | Lý do thay đổi | Đã tách khỏi |
|---|---|---|---|
| `Order` (`order/Order.kt:35`) | Giữ dữ liệu đơn, đồng bộ hóa, ủy quyền cho state | Cấu trúc dữ liệu đơn | Quy tắc theo trạng thái (sang `OrderState`) |
| `ConfirmedState` (`order/OrderState.kt:101`) | Quy tắc của đúng một trạng thái (phí hủy 10%) | Quy tắc trạng thái đó | `Order` và `CancelOrderUseCase` |
| `PricingService` (`pricing/Pricing.kt:31`) | Tạm tính, giảm giá, thuế, tổng | Công thức tính giá | `CheckoutProcess`, `EditOrderItemsUseCase` (cùng gọi) |
| `RefundExecutor` (`usecase/OrderUseCases.kt:22`) | Tìm processor đã thu tiền và hoàn tiền | Cách hoàn tiền | `CancelOrderUseCase`, `RefundOrderUseCase` (tránh trùng lặp) |
| `Cart` / `CartHistory` / `CartEditor` | Giữ dòng hàng / giới hạn backup / điều phối backup và nháp | Quy tắc giỏ / chính sách backup / quy trình lưu | Một lớp giỏ hàng làm cả ba |

**OCP.** Thêm **kênh thông báo CHAT**: một lớp mới cài `NotificationChannel` với `type = ChannelType("CHAT")` và một dòng ở `AppContainer.kt:77-79`. Không sửa `ChannelType` (value class, không phải enum), `Notification`, `MultiChannelNotifier`, `NotificationOrderListener` hay `Order`. Test `NotificationBridgeTest.aNewChannelIsOneNewClassAndNothingElseChanges` làm đúng việc này mà không chạm code `main`. Bằng chứng bổ sung: khu vực JP (mục 2.3), thêm observer chỉ cần `events.subscribe(...)`.

**LSP.** Hợp đồng chung mà mọi implementation phải giữ:

| Hợp đồng | Pre-condition | Post-condition / bất biến |
|---|---|---|
| `Payable.pay` (`payment/Payment.kt:35`) | `amount > 0`; đúng currency và method của processor | `receipt.amount == request.amount`; lỗi chỉ báo bằng `PaymentFailedException`, không trả `null` |
| `Refundable.refund` | Receipt cùng method, đã thu tiền; `0 < amount ≤ receipt.amount` | `refundedAmount == amount`; không hoàn vượt số đã thu |
| Mọi thao tác của `OrderState` | — | Hoặc thực hiện hiệu ứng, hoặc ném `IllegalOrderOperationException`; bị từ chối thì không đổi trạng thái, không phát event |

Sáu processor (VNPay, MoMo, COD-VN, Stripe, ApplePay, COD-US) đều kế thừa `SimulatedPaymentProcessor`, trong đó `pay` là `final override`, nên lớp con không thể nới hay siết pre-condition. Test: `RegionFactoryTest.everyProcessorHonoursTheSamePreAndPostConditions`, `refundMustNotExceedWhatWasPaid`; với `OrderState` là ma trận 39 tổ hợp ở mục 2.1.

**ISP.** Tách `PaymentProcessor { pay(); refund() }` thành `Payable` và `Refundable` (`payment/Payment.kt:30-49`). Nếu gộp, COD buộc phải cài `refund()` bằng cách ném `UnsupportedOperationException`: vi phạm LSP và chỉ lộ lỗi lúc chạy. Sau khi tách, COD chỉ cài `Payable` (COD thu tiền khi giao, không có gì để hoàn online); `CheckoutProcess` chỉ dùng `Payable`; `RefundExecutor` dùng `as? Refundable` và báo `ValidationException` rõ ràng nếu không hoàn được. Test: `RegionFactoryTest.onlyCardAndWalletAreRefundable`. Các interface hẹp khác: `OrderContext`, `CartSnapshot`, `CustomerNotifier`.

**DIP.** ViewModel phụ thuộc `OrderFacade`, `MenuRepository`, `NotificationOutbox`; `CheckoutProcess` và bốn use case phụ thuộc `OrderRepository`, `InventoryService`, `OrderEventPublisher`, `CustomerNotifier`, `Clock`; `MultiChannelNotifier` phụ thuộc `NotificationChannel`. Mọi implementation cụ thể được inject bằng **constructor injection** tại một nơi duy nhất là `app/AppContainer.kt`; không có Singleton hay service locator. Ngoại lệ có ý thức: `CartViewModel`/`CheckoutViewModel` dùng lớp domain cụ thể `CartEditor`, `SimulatorViewModel` dùng `OrderLifecycleService`, `PromoAnnouncer`. Chiều phụ thuộc vẫn đúng, các lớp này tự chỉ phụ thuộc abstraction, và mỗi lớp chỉ có một cài đặt nên tách interface là thừa. Test: `ViewModelTest.viewModelsDependOnlyOnDomainTypes`, `ArchitectureTest.domainDoesNotDependOnOuterLayers`.

## 4. Kiểm thử

144 unit test trong 11 test class, tất cả xanh; mỗi pattern có test class riêng (`OrderStateTest`, `OrderObserverTest`, `RegionFactoryTest`, `CheckoutTemplateTest`, `OrderFacadeTest`, `NotificationBridgeTest`, `CartMementoTest`), cùng `ViewModelTest` và `ArchitectureTest` cho MVVM. Line coverage của `com.nemis.foodhub.domain` là **97,7%** (mục tiêu 70%); chạy `./gradlew :core:test`, báo cáo JaCoCo ở `core/build/reports/jacoco/test/html/index.html`.

## 5. Hạn chế và Change Request

**Hạn chế đã biết.**

- Sửa món không quyết toán chênh lệch tiền với gateway; hủy xong mới hoàn tiền nên gateway lỗi thì đơn vẫn CANCELLED (hướng xử lý: bước thu thêm/hoàn bớt, hàng đợi retry).
- Observer đồng bộ: listener chậm làm chậm bên phát (hướng xử lý: dispatcher bất đồng bộ).
- Thanh toán, kênh, tồn kho là giả lập; hóa đơn PDF (bonus) chưa làm; thêm định dạng hóa đơn phải sửa `when(format)` trong `BaseInvoiceFormatter` (cố ý: trục mở rộng là khu vực, không phải định dạng).
- UI ở mức demo; đơn hàng lưu trong RAM nên mất khi process bị kill (nháp giỏ hàng thì không).
- Nháp mang giá cũ nếu menu đổi giá trong lúc app tắt (hướng xử lý: tra lại món qua `MenuRepository` khi khôi phục).

**Change Request dự kiến.**

| Change Request | Sửa / thêm gì | Không phải sửa |
|---|---|---|
| Thêm kênh thông báo | 1 lớp `NotificationChannel` + 1 dòng `AppContainer` | `Notification`, `Order`, observer |
| Thêm khu vực | 1 `RegionFactory` + 1 dòng `AppContainer` + mã khu vực trong dữ liệu nhà hàng | Mọi lớp hiện có, kể cả tầng Android |
| Thêm trạng thái (ví dụ READY_FOR_PICKUP) | 1 `OrderState` + sửa state đứng trước + 1 giá trị `OrderStatus` | State khác, ViewModel (capabilities tự cập nhật) |
| Thêm loại đơn | 1 lớp con `CheckoutProcess` + 1 giá trị `OrderType` + 1 dòng `AppContainer` | Facade, state |
| Lưu nháp ở nơi khác (DataStore, SQLite) | 1 lớp `CartDraftRepository` + 1 dòng `AppContainer` | `Cart`, `CartEditor`, ViewModel |
| Thêm phương thức thanh toán | 1 giá trị `PaymentMethod` + 1 nhánh ở mỗi `newPaymentProcessor` (trình biên dịch chỉ chỗ thiếu) | State, checkout, facade, ViewModel |
| Đổi phí hủy / cửa sổ hoàn tiền | Hằng số trong `order/OrderState.kt:14-15` | Use case, UI |
