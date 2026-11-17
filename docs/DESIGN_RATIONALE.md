# FoodHub: Design Rationale

Tài liệu giải thích các quyết định thiết kế của lõi xử lý đơn hàng FoodHub (Customer app). Mỗi pattern theo cấu trúc **Vấn đề → Lựa chọn → Đánh đổi**, kèm phương án đã loại bỏ. Tên lớp được ghi đúng như trong code; sơ đồ nằm ở `docs/uml/` (file `.puml` và ảnh PNG trong `docs/uml/img/`).

## 1. Phạm vi và các quyết định chung

**Phạm vi.** Kotlin, domain thuần JVM, dữ liệu in-memory, View là app Android Jetpack Compose 2 màn hình (module `:app`); domain, data, presentation nằm trong module Kotlin/JVM thuần `:core`, kèm demo console `MainKt`. Làm đủ C1, C2, C3, C4, C7 và **cả C5 lẫn C6** (bonus). State diagram là bonus (`13-state-diagram.puml`).

**Đề bài còn mơ hồ ở chín điểm. Quyết định và lý do:**

| # | Điểm mơ hồ | Quyết định | Lý do |
|---|---|---|---|
| 1 | Đơn pickup không có DELIVERING | Pickup đi `PREPARING → DELIVERED` (nhận hàng tại quầy). Không thêm trạng thái mới. | Đúng bảng đề bài; chỉ cần `OrderType.requiresCourier` để `PreparingState` chọn nhánh. |
| 2 | "Xác nhận" ở C4 so với CONFIRMED | Checkout tạo đơn ở **CREATED** sau khi thanh toán thành công; CONFIRMED là nhà hàng xác nhận. | Khớp "CREATED hủy miễn phí" (hoàn 100%) và "CONFIRMED hủy phí 10%" (giữ lại 10% số đã thu). |
| 3 | COD mâu thuẫn "đã thu tiền" | COD không giữ tiền lúc checkout (`PaymentReceipt.collected = false`); tiền được thu khi giao (`DeliveredState.onEnter`). Hủy đơn COD: phí 0, hoàn 0. Giao xong, đơn COD cũng không hoàn tiền online (state từ chối, `canRequestRefund = false`). | Không thu tiền thì không có gì để giữ phí hay hoàn. |
| 4 | Đơn hẹn giờ | Dùng chung state machine với giao tận nơi; giờ hẹn phải cách hiện tại 30 phút đến 7 ngày. | Khác biệt chỉ ở bước validate nên nằm ở `ScheduledCheckout`. |
| 5 | Cửa sổ hoàn tiền 24 giờ | `Clock` được inject; đúng 24h vẫn hợp lệ, quá 24h bị `RefundWindowExpiredException`; chỉ hoàn một lần. | Test xác định, không phụ thuộc giờ hệ thống. |
| 6 | Phí hủy 10% đặt ở đâu | Trong `ConfirmedState.cancel` (quy tắc theo trạng thái). `CancelOrderUseCase` chỉ chuyển tiền. | State quyết định số tiền; use case không biết quy tắc. |
| 7 | C2 và C5 dễ lẫn | Observer quyết định *ai được báo* (`NotificationOrderListener`), Bridge quyết định *báo bằng cách nào* (`Notification` × `NotificationChannel`). | Hai pattern giải hai vấn đề khác nhau và được nối bằng `CustomerNotifier`. |
| 8 | Coverage ≥ 70% trong 2 ngày | Thiết kế để mỗi pattern test độc lập; 124 test; đo bằng JaCoCo (mục 7). | Domain không phụ thuộc framework nên test không cần emulator. |
| 9 | Change Request chưa biết | Tự kiểm tra bằng các kịch bản ở mục 6. | Chuẩn bị cho phần bảo vệ. |

Hai chỗ code chủ động **mở rộng được** thay vì dùng enum: `Currency` và `ChannelType` là value type (không phải `enum`), vì thêm tiền tệ (JPY) hay kênh mới (chat) mà phải sửa enum thì vi phạm OCP.

## 2. Design pattern

### C1. State: vòng đời đơn hàng (`OrderState`, `Order`)

**Vấn đề.** Sửa món, hủy, hoàn tiền thay đổi theo trạng thái; chuyển trạng thái sai phải bị từ chối rõ ràng; không được có chuỗi `if/when` trên enum.

**Lựa chọn.** `OrderState` là lớp trừu tượng **từ chối mọi thao tác theo mặc định**; mỗi state (`CreatedState`… `CancelledState`) chỉ override thao tác nó cho phép. `Order` là context, mọi hành vi ủy quyền cho `state`. State chỉ thấy `OrderContext` (interface hẹp), không thấy toàn bộ `Order`. `OrderStatus` chỉ là nhãn mô tả (có `customerMessage`), không chọn hành vi. Mỗi state công bố `OrderCapabilities` (sửa, hủy, phí, hoàn tiền, đánh giá, xem courier) nên ViewModel không phải tự lặp lại quy tắc. Quy tắc tĩnh nằm ở `capabilities`; `capabilitiesFor(ctx)` tinh chỉnh theo dữ kiện của chính đơn đó (ví dụ `DeliveredState` chỉ cho hoàn tiền khi đã trả online, chưa hoàn và còn trong 24 giờ; chỉ cho đánh giá một lần), nên UI không bao giờ hiện nút mà domain sẽ từ chối.

**Bằng chứng.** `OrderStateTest.everyOperationOutsideTheAllowedSetIsRejectedWithoutSideEffects` thử 8 thao tác × 6 trạng thái: mọi tổ hợp không hợp lệ ném `IllegalOrderOperationException`, không đổi trạng thái, không phát event. `ArchitectureTest.noBranchingOnOrderStatusAnywhereInTheDomain` quét mã nguồn tìm `when/if` theo status, cả so sánh với hằng `OrderStatus.X` lẫn `status/current/previous ==`. Mức khẩn cấp của thông báo là dữ liệu của status (`OrderStatus.urgent`), nên `NotificationOrderListener` không so sánh status nào.

**Đánh đổi.**
- Thêm 6 lớp và một interface `OrderContext`; đổi lại việc thêm trạng thái mới (ví dụ READY_FOR_PICKUP) chỉ là thêm một lớp và sửa nơi chuyển tới nó.
- Các state là `object` (stateless, kiểu flyweight), không phải service locator. `ArchitectureTest` cấm `object` ở domain ngoài file `OrderState.kt`.
- `PreparingState` có kiểm tra `type.requiresCourier`. Đó là rẽ nhánh theo *loại đơn*, không theo trạng thái, và là cách ít lớp nhất để xử lý pickup.

**Phương án loại bỏ.** Enum + `when` ở mọi nơi (đúng lỗi đề bài cảnh báo); bảng chuyển trạng thái dạng dữ liệu (không gắn được hành vi như phí hủy và cửa sổ hoàn tiền).

### C2. Observer: theo dõi đơn thời gian thực (`OrderEventPublisher`, `OrderStatusListener`)

**Vấn đề.** UI, thông báo và analytics cùng phản ứng khi đổi trạng thái; nguồn phát không biết ai đang nghe; phải xử lý đăng ký/hủy và luồng.

**Lựa chọn.** `Order` chỉ biết interface `OrderEventPublisher`. `subscribe()` trả về `Subscription` để hủy. Ba observer thật: `OrderTrackingViewModel` (UI), `NotificationOrderListener`, `OrderStatusCounter` (analytics), đăng ký ở composition root.
- **Luồng:** `Order` phát event **sau khi nhả lock**; test `orderPublishesAfterReleasingItsLockSoListenersCanReadItFromAnotherThread` chứng minh listener đọc order từ thread khác không deadlock.
- **Danh sách đăng ký** là `CopyOnWriteArrayList` (listener được hủy ngay trong lúc nhận event); listener lỗi bị cô lập, không chặn listener khác.
- **UI:** listener chạy trên thread phát; ViewModel ghi vào `StateFlow` (thread-safe), View thu thập trên main thread.
- **Rò rỉ:** `OrderTrackingViewModel.onCleared()` hủy subscription (có test).
- **Thao tác không đổi status** (sửa món, hoàn tiền, đánh giá) không phát event; sau khi thao tác thành công ViewModel đọc lại trạng thái từ domain (`facade.getOrder`), nên luồng vẫn một chiều và UI không tự tính quy tắc.

**Đánh đổi.** Gọi đồng bộ trên thread của bên phát nên listener chậm làm chậm bên phát (chấp nhận được ở quy mô này; có thể thêm dispatcher bất đồng bộ sau). Thứ tự gọi listener theo thứ tự đăng ký, không bảo đảm gì hơn. Giữa `getOrder` và `subscribe` ở ViewModel có một khe hở rất hẹp, ghi ở mục Hạn chế.

**Phương án loại bỏ.** Polling (chậm, tốn); chỉ dùng `LiveData` trong domain (làm domain phụ thuộc Android); EventBus toàn cục (ẩn phụ thuộc, khó test).

### C3. Abstract Factory (+ Factory Method): thanh toán đa khu vực (`RegionFactory`)

**Vấn đề.** Mỗi khu vực cần một bộ `PaymentProcessor`, `TaxCalculator`, `InvoiceFormatter` đi cùng nhau; thêm khu vực không sửa code cũ.

**Lựa chọn.** `RegionFactory` (Abstract Factory) tạo trọn bộ; `VietnamRegionFactory`, `UsRegionFactory` là hai họ cụ thể, nên không thể ghép VAT Việt Nam với hóa đơn Mỹ. `createPaymentProcessor(method)` là **Factory Method**: điểm vào cố định, giao việc chọn processor cho hook `newPaymentProcessor`, rồi kiểm tra hợp đồng (đúng method, đúng currency) bằng `check`. `RegionRegistry` tra cứu theo mã khu vực.

**Bằng chứng OCP.** Test `aNewRegionWithItsOwnCurrencyWorksEndToEndWithoutChangingExistingCode` định nghĩa `JapanRegionFactory` (có tiền tệ JPY, thuế, hóa đơn riêng) hoàn toàn trong code test, rồi chạy checkout và hủy đơn thành công qua code hiện có.

**Đánh đổi.** Thêm một *loại sản phẩm* mới vào họ (ví dụ `ShippingCalculator`) phải sửa mọi factory; đây là nhược điểm kinh điển của Abstract Factory, chấp nhận vì tập sản phẩm ổn định. Đăng ký khu vực mới vẫn là một dòng ở composition root (không thể tránh nếu không dùng `ServiceLoader`, quá mức cần thiết ở đây). Bộ processor là bản giả lập; gateway thật sẽ được inject từ Data layer qua constructor.

**Phương án loại bỏ.** Một factory với `when(region)` (vi phạm OCP); Strategy riêng cho thuế/hóa đơn/thanh toán (cho phép ghép sai họ).

### C4. Template Method: quy trình checkout (`CheckoutProcess`)

**Vấn đề.** Các bước cố định validate → tính giá → thanh toán → xác nhận → thông báo, nhưng mỗi loại đơn khác nhau ở vài bước.

**Lựa chọn.** `CheckoutProcess.checkout()` là template method và **final** (có test reflection). Ba hook phân biệt loại đơn: `validateTypeRules`, `deliveryFee`, `placedMessage`. `DeliveryCheckout` cần địa chỉ và thu phí giao; `PickupCheckout` cấm giờ hẹn và không thu phí; `ScheduledCheckout` kiểm tra khoảng thời gian 30 phút đến 7 ngày. Các bước còn lại là `protected open` để test ghi lại thứ tự gọi (`stepsRunInTheFixedOrderValidatePricePayConfirmNotify`).

**Xử lý lỗi.** Thanh toán thất bại thì không để lại dấu vết. Nếu bước xác nhận lỗi sau khi đã thu tiền thì hoàn tiền và trả kho trước khi ném lại lỗi (có hai test bù trừ).

**Đánh đổi.** Dùng kế thừa nên ba lớp con gắn chặt với lớp cha; chỉ có ba biến thể và chúng khác nhau rất ít nên chấp nhận. Nếu số biến thể tăng nhiều hoặc cần ghép chéo (ví dụ giao hẹn giờ có phí riêng), nên chuyển sang Strategy.

**Phương án loại bỏ.** Một lớp checkout với `if (type == …)` ở từng bước; Strategy cho từng bước (nhiều đối tượng nhỏ nhưng không có thứ tự được ép).

### C7. Facade: điểm vào cho ViewModel (`OrderFacade`)

**Vấn đề.** ViewModel chỉ gọi một API mức cao và không biết Inventory, Pricing, Payment, Notification; không để thành God class.

**Lựa chọn.** `OrderFacade` có 8 phương thức (gồm `exportInvoice`, ủy quyền cho `ExportInvoiceUseCase`); `DefaultOrderFacade` **chỉ ủy quyền**: chọn `CheckoutProcess` theo `OrderType` và gọi use case. Quy tắc nghiệp vụ nằm ở state, template và use case, không ở facade. Test `facadeDoesNotKnowInventoryPricingPaymentOrNotification` kiểm tra bằng reflection rằng các trường và chữ ký không chứa các kiểu đó, và `facadeApiStaysSmall` giới hạn kích thước API.

**Đánh đổi.** Thêm một tầng gián tiếp; đổi lại ViewModel dễ test và domain đổi bên trong không ảnh hưởng UI. `OrderLifecycleService` (mô phỏng app Restaurant/Courier) cố ý tách khỏi facade để facade không phình ra.

**Phương án loại bỏ.** ViewModel gọi thẳng từng service (lộ phụ thuộc); một `CheckoutService` chứa mọi logic (chính là God class).

### C5. Bridge: thông báo đa kênh (`Notification` × `NotificationChannel`)

**Vấn đề.** Ba loại (OrderUpdate, Promo, Urgent) × ba kênh (Push, SMS, Email) phát triển độc lập, tránh bùng nổ lớp.

**Lựa chọn.** Hai hệ thống phân cấp độc lập nối bằng một tham chiếu: `Notification` (nói *gì*, có `buildMessage()`) giữ `NotificationChannel` (gửi *thế nào*). 3 + 3 = 6 lớp thay vì 9; thêm kênh thứ tư là đúng một lớp. Định dạng riêng của kênh (SMS cắt còn 160 ký tự, email thêm tiền tố tiêu đề) nằm ở kênh. `MultiChannelNotifier` gửi qua các kênh khách đã chọn; `PromoAnnouncer` (đại diện công cụ Admin) dùng chính đường này để báo khuyến mãi; một kênh lỗi được báo qua callback và không chặn các kênh khác.

**Đánh đổi.** Thêm một lớp trừu tượng so với gọi thẳng kênh; có lợi khi số loại và số kênh cùng tăng. Test `aNewChannelIsOneNewClassAndNothingElseChanges` thêm kênh `CHAT` mà không sửa code cũ.

**Phương án loại bỏ.** Kế thừa theo tích (`PushOrderUpdate`, `SmsPromo`… 9 lớp); một lớp với `when(channel)`.

### C6. Memento: nháp và undo giỏ hàng (`Cart`, `CartSnapshot`, `CartHistory`)

**Vấn đề.** Lưu/khôi phục snapshot mà không phá encapsulation; nhiều bản backup có giới hạn.

**Lựa chọn.** `Cart` (originator) tạo `Memento` là **lớp nested private**: chỉ `Cart` đọc được nội dung. Caretaker (`CartHistory`, `CartDraftRepository`) chỉ thấy interface `CartSnapshot` với đúng một thuộc tính `takenAt` (có test xác nhận). `CartHistory` giới hạn dung lượng và bỏ bản cũ nhất khi đầy. `CartEditor` backup trước mỗi thay đổi *thành công* (thay đổi lỗi không để lại gì) và tự lưu nháp để khôi phục khi app bị đóng đột ngột; khôi phục nháp cũng undo được.

**Đánh đổi.** Mỗi lần thay đổi sao chép map dòng hàng (rẻ vì `CartItem` bất biến). Nháp đang lưu trong bộ nhớ; để sống sót qua việc app bị kill thật, cần bộ mã hóa do `Cart` cung cấp (ghi ở Hạn chế). Chưa có redo.

**Phương án loại bỏ.** Để lộ các trường của `Cart` rồi copy (phá encapsulation); Command pattern với lệnh đảo (phức tạp hơn nhiều cho cùng kết quả).

### Không dùng pattern nào cho có

Những chỗ cố ý **không** dùng pattern: `PricingService` là hàm thuần; `RefundExecutor` là một lớp nhỏ, không phải Strategy; không có Singleton nào ở domain; một `CheckoutServices` gom collaborator thay vì tạo thêm lớp trung gian.

## 3. Bằng chứng SOLID

| Nguyên tắc | Bằng chứng (lớp/test) |
|---|---|
| **SRP** | `Order` giữ trạng thái và ủy quyền cho state; `PricingService` chỉ tính giá (đổi khi công thức giá đổi); `RefundExecutor` chỉ chuyển tiền hoàn (đổi khi cách hoàn tiền đổi); `MultiChannelNotifier` chỉ phát thông báo; phí hủy nằm riêng ở `ConfirmedState`. |
| **OCP** | Thêm khu vực JP: test `aNewRegionWithItsOwnCurrencyWorks…`. Thêm kênh `CHAT`: test `aNewChannelIsOneNewClassAndNothingElseChanges`. Thêm observer: `events.subscribe(...)` không sửa `Order`. |
| **LSP** | Mọi `PaymentProcessor` do mọi factory tạo ra thay thế được cho nhau: test `everyProcessorHonoursTheSamePreAndPostConditions` chạy cả 2 khu vực × 3 phương thức. Pre: amount > 0, đúng currency, đúng method. Post: `receipt.amount == request.amount`; lỗi báo bằng `PaymentFailedException`, không trả null. Với `OrderState`: mỗi thao tác hoặc thực hiện hoặc ném `IllegalOrderOperationException`, không bao giờ im lặng bỏ qua (test ma trận: 39 tổ hợp thao tác-trạng thái không hợp lệ đều bị từ chối). Bất biến `Money`: cùng currency, đã làm tròn theo số chữ số của tiền tệ. |
| **ISP** | `Payable` và `Refundable` tách riêng: COD chỉ `Payable`, thẻ/ví thêm `Refundable` (`RegionFactoryTest.onlyCardAndWalletAreRefundable`). `OrderContext` là view hẹp của `Order` dành cho state. `NotificationChannel`, `TaxCalculator`, `InvoiceFormatter` mỗi interface một việc. |
| **DIP** | `OrderTrackingViewModel`, `CheckoutViewModel`, `InvoiceViewModel` chỉ phụ thuộc `OrderFacade`; `CartViewModel` phụ thuộc `CartEditor` và `MenuRepository`; `MenuViewModel` chỉ phụ thuộc `MenuRepository`; use case chỉ phụ thuộc interface repository đặt ở domain. Nơi inject: **`AppContainer`** (composition root, constructor injection). `ViewModelTest.viewModelsDependOnlyOnDomainAbstractions` và `ArchitectureTest` kiểm tra. Không dùng Singleton làm đường tắt. |

## 4. Kiến trúc MVVM

- **Presentation** (`com.nemis.foodhub.presentation`): `MenuViewModel` (xem menu), `CartViewModel`, `CheckoutViewModel`, `OrderTrackingViewModel` (theo dõi, hủy, sửa món, hoàn tiền, đánh giá), `InvoiceViewModel` (xuất hóa đơn HTML/CSV) expose `StateFlow` của các `UiState` bất biến; không giữ View hay Context; không chứa quy tắc nghiệp vụ (`ArchitectureTest.businessRulesAreNotInThePresentationLayer`). View là hai màn hình Compose `OrderingScreen` (menu, giỏ hàng, checkout) và `OrderTrackingScreen` (theo dõi, thao tác, hóa đơn, thông báo); chúng chỉ `collectAsStateWithLifecycle()` state và chuyển sự kiện cho ViewModel.
- **Domain** (`com.nemis.foodhub.domain`): entity, use case, các pattern; **không** import `android.*` hay `kotlinx.coroutines` (test kiểm tra); interface repository đặt ở đây.
- **Data** (`com.nemis.foodhub.data`): triển khai in-memory, nằm ngoài cùng.
- **Chiều phụ thuộc:** Presentation → Domain ← Data, được `ArchitectureTest` quét theo từng import. Luồng một chiều: sự kiện → domain → state mới → View cập nhật (xem sequence 2).
- **Tách module:** `:core` (Kotlin/JVM, không có Android plugin) chứa domain, data, presentation và `AppContainer`, nên domain không thể import `android.*` và toàn bộ test chạy không cần emulator. `:app` (Android, Compose) phụ thuộc `:core`.
- ViewModel trong `:core` kế thừa `BaseViewModel` thuần JVM. Ở `:app`, `OrderingViewModels` và `OrderScreenViewModels` (kế thừa `androidx.lifecycle.ViewModel`) bọc chúng để sống qua xoay màn hình và chuyển `onCleared()` của framework xuống `BaseViewModel.onCleared()`, nhờ đó subscription Observer của `OrderTrackingViewModel` được hủy khi rời màn hình. Các lớp bọc này không chứa state hay quy tắc nghiệp vụ.
- Nhà hàng, shipper và admin là app khác; trên màn hình theo dõi chúng được giả lập bằng các nút gọi `OrderLifecycleService` và `PromoAnnouncer`.

## 5. Hạn chế đã biết

1. **Sửa món không quyết toán chênh lệch tiền** với gateway; giá được tính lại nhưng số đã thu không đổi.
2. **Hủy xong mới hoàn tiền:** nếu gateway lỗi sau khi đơn đã CANCELLED thì đơn vẫn bị hủy và cần retry hoàn tiền (cần hàng đợi trong hệ thống thật).
3. **Nháp giỏ hàng chỉ lưu trong bộ nhớ.** Để sống sót khi app bị kill thật cần serialize snapshot qua bộ mã hóa do `Cart` cung cấp.
4. **Khe hở nhỏ khi theo dõi:** `OrderTrackingViewModel` đọc trạng thái ban đầu rồi mới đăng ký; một event xen giữa hai bước có thể bị bỏ lỡ. Cách sửa: kèm số phiên bản vào event và đọc lại sau khi đăng ký.
5. **Observer đồng bộ** nên listener chậm làm chậm bên phát.
6. **Thanh toán, kênh thông báo, tồn kho là bản mô phỏng**; thuế/phí là số giả lập. Hóa đơn PDF (bonus) chưa làm; `InvoiceFormat` mới có HTML và CSV.
7. **UI Android ở mức demo:** 2 màn hình, chưa có Navigation component; khách hàng và vùng của nhà hàng là dữ liệu cố định trong `FoodHubApplication`. Đơn hàng in-memory nên mất khi process bị kill.
8. Hai pattern gây tranh cãi có thể bị hỏi khi bảo vệ: Template Method dùng kế thừa (mục C4) và Abstract Factory khó thêm loại sản phẩm mới (mục C3).

## 6. Sẵn sàng cho Change Request

| Change Request có khả năng | Sửa gì | Không phải sửa |
|---|---|---|
| Thêm phương thức thanh toán (ví dụ trả góp) | `PaymentMethod` thêm giá trị; mỗi `RegionFactory.newPaymentProcessor` thêm một nhánh | State, checkout, facade, ViewModel |
| Thêm khu vực | Một `RegionFactory` + đăng ký ở `AppContainer` | Mọi lớp hiện có |
| Thêm kênh thông báo | Một lớp `NotificationChannel` + đưa vào `AppContainer` | `Notification`, `Order`, observer |
| Thêm loại thông báo | Một lớp con của `Notification` + một phương thức ở `CustomerNotifier` | Các kênh |
| Thêm trạng thái (READY_FOR_PICKUP) | Một `OrderState` + sửa trạng thái trước đó trỏ tới nó + thêm giá trị `OrderStatus` | Các state còn lại, ViewModel (capabilities tự thay đổi) |
| Đổi phí hủy hoặc cửa sổ hoàn tiền | Hằng số trong `OrderState.kt` | Use case, UI |
| Thêm loại đơn mới | Một `CheckoutProcess` con + một `OrderType` + đăng ký ở `AppContainer` | Facade, state |
| Thêm observer (ví dụ gửi log) | Một `OrderStatusListener` + `events.subscribe` | `Order`, publisher |

## 7. Kiểm thử và chất lượng

- **124 unit test, tất cả xanh**, gồm: ma trận chuyển trạng thái hợp lệ/không hợp lệ (C1); observer được notify, hủy đăng ký, đa luồng (C2); factory theo khu vực và khu vực mới (C3); thứ tự các bước template và bù trừ khi lỗi (C4); facade (C7); bridge (C5); memento (C6); ViewModel; luồng khách hàng (menu, xuất hóa đơn, sửa món, khuyến mãi, capabilities theo từng đơn); và kiểm tra kiến trúc bằng cách quét mã nguồn.
- **Line coverage của `com.nemis.foodhub.domain` đo bằng JaCoCo (`./gradlew :core:test`) là 97,5% (670/687 dòng)**, vượt mục tiêu ≥ 70%.
- Build Gradle đã được chạy thật (Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, JDK 17+), có `gradlew` kèm theo. App đã chạy trên emulator API 34.
- Khi gắn UI Android, phát hiện và sửa thêm một lỗi: sửa món bằng món của nhà hàng khác làm phép cộng tiền ném `IllegalArgumentException` (không phải `DomainException`, nên app crash). `EditOrderItemsUseCase` nay từ chối bằng `ValidationException` (test `editingWithItemsOfAnotherRestaurantIsAValidationErrorAndChangesNothing`).
- Test phát hiện và giúp sửa một lỗi thật: `CartViewModel` ghi đè trạng thái lỗi ngay sau khi đặt nó.
