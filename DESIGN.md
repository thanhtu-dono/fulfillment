# Order Fulfillment Engine
## Architecture and Implementation Plan

> Tài liệu này được viết trước khi khởi tạo project. Đây là baseline để review kiến trúc, phạm vi và các quyết định triển khai.

## 1. Mục tiêu

Xây dựng ứng dụng console Java 17+ mô phỏng hệ thống xử lý đơn hàng đồng thời từ nhiều fulfillment center. Ứng dụng phải:

- Đọc inventory và order feed từ flat file.
- Parse nghiêm ngặt giao thức OFP v1.
- Reservation an toàn khi nhiều thread cùng xử lý đơn hàng.
- Hỗ trợ multi-center reservation, rollback, backorder, escalation và dead-letter.
- Cung cấp audit log, báo cáo và stress test có kiểm chứng.
- Chạy bằng `javac` và `java`, không dùng framework hoặc thư viện ngoài JDK.

## 2. Skills / năng lực áp dụng

- **Java 17+**: records, enum, immutable domain objects, try-with-resources, sealed types khi phù hợp.
- **Java concurrency**: `ExecutorService`, `ScheduledExecutorService`, `ConcurrentHashMap`, `BlockingQueue`, `ReentrantLock`, `AtomicBoolean`, `CountDownLatch`.
- **Thread-safety và atomicity**: bảo vệ inventory theo từng resource, lock ordering cố định, rollback và state transition.
- **File I/O**: `java.nio.file.Files`, `Path`, buffered streaming và xử lý lỗi từng dòng.
- **Custom protocol parsing**: parser OFP v1, checksum, continuation record và reason code.
- **Domain-driven modeling**: tách domain, application service, infrastructure và console interface.
- **Testing không framework**: test harness Java Core, assertions thủ công, concurrency stress test và watchdog.
- **Observability tối thiểu**: audit trail, rejects log, report và output kiểm chứng.
- **Documentation**: ghi rõ assumptions, trade-off, deadlock proof, bug reflection và limitations.

## 3. Tech stack

| Hạng mục | Lựa chọn |
|---|---|
| Ngôn ngữ | Java 17+ |
| Runtime | JDK 17+ |
| Build | `javac` / script command line, không bắt buộc Maven hoặc Gradle |
| Dependency | Chỉ Java Standard Library |
| Input | `inventory_seed.txt`, `order_feed.txt` |
| Storage | In-memory |
| Concurrency | `java.util.concurrent`, `java.util.concurrent.locks` |
| Logging | File I/O Java Core cho `rejects.log`; audit lưu in-memory |
| Test | Java executable test harness, không JUnit |
| Interface | Interactive console qua stdin/stdout |

Không sử dụng Spring, Hibernate, Lombok, Jackson, JUnit hoặc bất kỳ dependency bên thứ ba nào.

## 4. Kiến trúc đề xuất

Áp dụng **Layered Architecture kết hợp Ports and Adapters ở mức nhẹ**:

```text
Console / Stress Test
        |
Application Services
        |
Domain Model + Reservation Rules
        |
Ports (interfaces)
        |
In-memory Inventory | File Parser | Audit | Reject Writer
```

### 4.1 Package dự kiến

```text
src/
  com.example.fulfillment/
    Main.java
    domain/
    application/
    inventory/
    protocol/
    backorder/
    audit/
    console/
    stress/
    support/
```

- `domain`: model bất biến, enum và trạng thái nghiệp vụ.
- `protocol`: đọc inventory, parse OFP, checksum và validation.
- `inventory`: inventory store và reservation engine.
- `application`: orchestration xử lý order, backorder và report.
- `backorder`: queue, retry và escalation worker.
- `audit`: audit event và thread-safe audit trail.
- `console`: command parser và vòng lặp tương tác.
- `stress`: stress-test harness và verification output.
- `support`: clock, configuration và các utility nhỏ dùng chung.

## 5. Design patterns

- **Repository pattern**: ẩn cấu trúc lưu inventory và order state.
- **Strategy pattern**: chiến lược chọn fulfillment center cho từng line item; có thể thay đổi heuristic mà không đổi reservation engine.
- **Command pattern**: ánh xạ `STATUS`, `REPORT`, `RESTOCK`, `AUDIT`, `STRESS-TEST`, `EXIT` thành console command handlers.
- **Observer / event publication đơn giản**: RESTOCK phát tín hiệu để backorder worker reprocess.
- **Service Layer**: điều phối use case ở application layer.
- **Factory / parser result**: tạo parsed records hoặc rejection result từ raw protocol line.
- **State transition bằng enum và service rule**: không cho phép cập nhật trạng thái tùy ý từ console.

Không áp dụng pattern chỉ để tăng số lượng class; mỗi pattern phải phục vụ một boundary hoặc một biến động thực tế.

## 6. Domain models chính

### 6.1 Inventory và fulfillment

- `Sku`: mã SKU hợp lệ.
- `FulfillmentCenter`: `FC-EAST`, `FC-WEST`, `FC-CENTRAL` hoặc center hợp lệ trong seed.
- `InventoryKey`: cặp `(Sku, FulfillmentCenter)` dùng làm lock/resource key.
- `InventoryItem`: stock không âm và unit price cố định.
- `InventorySnapshot`: snapshot stock dùng cho `STATUS` và report.
- `ReservationAllocation`: line item được phân bổ tại một center.
- `Reservation`: tập allocations của một order trong một thao tác nguyên tử.

### 6.2 Order

- `OrderId`: định dạng `ORD-` + đúng 6 chữ số.
- `OrderTier`: `STANDARD`, `PRIORITY`.
- `OrderFlags`: `partialAllowed` và reserved flag đã normalize.
- `OrderLine`: SKU và quantity dương.
- `Order`: order bất biến gồm id, tier, flags, lines và submission timestamp.
- `OrderStatus`: `RECEIVED`, `RESERVED`, `BACKORDERED`, `ESCALATED`, `DEAD_LETTERED`, `SHIPPED`, `REJECTED`.
- `OrderRecord`: order cùng trạng thái hiện tại và metadata xử lý.
- `DeadLetterLine`: line item bị dead-letter cùng reason và thời điểm.
- `BackorderEntry`: order/line pending, tier hiện tại, original timestamp và sequence number.
- `FulfillmentResult`: kết quả reservation: shipped, backordered, dead-lettered hoặc partial result.

### 6.3 Audit và báo cáo

- `AuditEvent`: timestamp, order id tùy chọn, event type, message và metadata.
- `AuditEventType`: accepted, reservation succeeded, rollback, backordered, escalated, dead-lettered, restocked, shipped.
- `ReportSnapshot`: revenue, success rate, shipped count, dead-letter count và inventory summary.
- `RejectRecord`: raw line, reason code và timestamp.

## 7. DTO, request và response

Ứng dụng không có HTTP API. Các DTO dưới đây là object contract giữa các application service và console/test harness.

### 7.1 Requests

- `SubmitOrderRequest`: parsed `Order` cần đưa vào engine.
- `RestockRequest`: SKU, center và quantity.
- `AuditQueryRequest`: order id.
- `ConsoleCommandRequest`: command name và arguments đã tokenize.
- `StressTestRequest`: số orders, số producer threads, SKU mục tiêu, time scale và timeout.
- `ParseLineRequest`: raw line và line number.

### 7.2 Responses

- `SubmitOrderResponse`: order id, status, allocations, rejection/dead-letter details.
- `RestockResponse`: SKU, center, quantity applied và số backorder được đánh thức.
- `StatusResponse`: inventory snapshot, queue counts và dead-letter counts.
- `ReportResponse`: revenue, success rate, counts và stress-test summary.
- `AuditResponse`: danh sách audit events của order.
- `ParseResult`: success với parsed record hoặc failure với reason code.
- `StressTestResult`: completed/timed out, invariant checks và failure details.
- `OperationResult`: success/failure, message và optional reason code.

## 8. Danh sách application API

Các API này là interface nội bộ, không phải REST endpoint:

```text
OrderFulfillmentService.submit(Order order)
OrderFulfillmentService.reprocessBackorders()
OrderFulfillmentService.restock(RestockRequest request)
OrderFulfillmentService.status()
OrderFulfillmentService.report()
OrderFulfillmentService.audit(String orderId)
OrderFulfillmentService.shutdown()

InventoryRepository.snapshot()
InventoryRepository.restock(Sku sku, FulfillmentCenter center, int quantity)
ReservationService.tryReserve(Order order)
ReservationService.companyCapacity(Sku sku)

BackorderService.enqueue(...)
BackorderService.processAvailable()
BackorderService.escalateEligibleOrders(...)

OrderFeedParser.parseHeader(...)
OrderFeedParser.parseContinuation(...)
InventorySeedReader.load(...)
RejectWriter.write(RejectRecord record)
AuditTrail.append(AuditEvent event)
```

## 9. Luồng nghiệp vụ

### 9.1 Startup

1. Validate Java/runtime configuration và time-scale.
2. Load `inventory_seed.txt`.
3. Validate SKU, center, quantity, price và duplicate inventory key.
4. Load danh sách SKU hợp lệ vào parser.
5. Khởi tạo inventory repository, audit trail, backorder service và workers.
6. Mở `order_feed.txt` và phân phối batch cho ít nhất 4 ingestion workers.

### 9.2 Parse order feed

1. Đọc raw line theo thứ tự file.
2. Xác định header `O|` hoặc continuation `C|`.
3. Validate checksum trước khi xử lý nội dung nghiệp vụ.
4. Validate order id, tier, flags, token count và line-item format.
5. Reserved flag vị trí 1 khác `-` được warning và normalize thành `-`.
6. Kiểm tra SKU tồn tại.
7. Header hợp lệ tạo order context; continuation hợp lệ nối line item vào header trước đó.
8. Mỗi lỗi bị ghi vào `rejects.log` với reason code và không dừng ingestion.
9. Duplicate header order id bị reject; continuation không có header tương ứng bị reject.
10. Khi order hoàn tất, gửi vào application service để reservation.

Việc phân phối line cho worker phải giữ được ngữ nghĩa continuation. Implementation phân đoạn feed thành logical order blocks tuần tự, sau đó parse các block bằng 4 worker; vì một block luôn chứa header và các continuation liên quan nên thứ tự protocol được bảo toàn trong khi parsing các order độc lập chạy đồng thời.

### 9.3 Reservation all-or-nothing

1. Chuẩn hóa các line item và tạo danh sách resource candidate.
2. Chọn đúng một center cho mỗi line item có đủ quantity.
3. Sắp xếp toàn bộ `InventoryKey` theo canonical order `(center name, SKU)`.
4. Acquire các lock theo đúng thứ tự tăng dần.
5. Kiểm tra lại stock dưới lock.
6. Nếu đủ toàn bộ: deduct inventory và tạo reservation trong cùng critical section.
7. Nếu thiếu: không deduct phần nào, release toàn bộ lock và trả failure.
8. Chuyển order sang shipped/reserved theo một state transition được service bảo vệ.
9. Ghi audit event tương ứng.

Để tránh oversell, không được kiểm tra stock trước rồi deduct sau khi đã release lock. Mọi kiểm tra và deduction phải nằm trong cùng lock scope.

### 9.4 Partial fulfillment

Với `PARTIAL_OK`:

1. Phân loại từng line thành fulfillable-now, temporarily unavailable hoặc never-fulfillable.
2. Fulfill các line hiện có stock.
3. Đưa line thiếu stock nhưng còn khả năng fulfill vào backorder.
4. Đưa line vượt company capacity vào dead-letter.
5. Order được xem là shipped/partially completed nếu có ít nhất một line đã fulfill; các pending line vẫn giữ metadata để retry.

Quyết định terminal: dead-letter line item không tự revive sau RESTOCK. RESTOCK chỉ đánh thức các line đang backorder; dead-letter cần thao tác thủ công hoặc cơ chế riêng trong tương lai.

### 9.5 Backorder và escalation

- Order thiếu stock nhưng còn khả năng fulfill được enqueue với original submission timestamp.
- RESTOCK phát signal, worker đánh thức và retry queue mà không chặn ingestion.
- Daemon worker định kỳ kiểm tra escalation; RESTOCK phát signal qua `Semaphore` để daemon reprocess queue mà không chặn console caller. Worker dùng priority queue: `PRIORITY` trước, sau đó original timestamp, rồi sequence number.
- Standard order chờ quá 90 simulated seconds và có priority order đang chờ sẽ escalate.
- Tie-break escalation: original submission timestamp tăng dần; nếu bằng nhau dùng immutable ingestion sequence tăng dần.
- Escalation và dequeue được thực hiện trong một lock của backorder service để tránh xử lý hai lần.

### 9.6 Console

- `STATUS`: in stock theo SKU/center và số lượng queue/dead-letter.
- `RESTOCK <SKU> <CENTER> <QTY>`: validate SKU, center và quantity dương; apply restock và trigger retry.
- `REPORT`: in revenue, fulfillment success rate và dead-letter count.
- `AUDIT <ORDER_ID>`: in audit events theo order.
- `STRESS-TEST`: chạy harness với ít nhất 8 producer threads và 5.000 orders.
- `EXIT`: stop accepting orders, chờ in-flight operations, stop workers, in final report rồi terminate.

## 10. Test plan

Không dùng JUnit. Test sẽ là các Java class executable với assertion thủ công và exit code khác 0 khi fail.

### 10.1 Parser và validation

- Parse header hợp lệ một line.
- Parse header có tối đa 4 line items.
- Parse continuation đúng order id.
- Reject checksum sai: `CHECKSUM_MISMATCH`.
- Reject order id, tier, flags sai: `MALFORMED_FIELD`.
- Accept reserved flag bất kỳ ở vị trí 1 và normalize về `-`.
- Reject token dùng dấu phẩy thay vì `x`.
- Reject quantity bằng 0, âm hoặc không phải số.
- Reject SKU không tồn tại.
- Reject duplicate order id.
- Reject orphan continuation.
- Một dòng lỗi không làm dừng các dòng sau.
- Kiểm tra `rejects.log` có raw line, reason code và timestamp.
- Kiểm tra parse concurrent vẫn giữ continuation và duplicate order ID deterministic.

### 10.2 Inventory và reservation

- Seed inventory đúng stock và price.
- Reject stock âm, price không hợp lệ và duplicate `(SKU, center)`.
- Reservation một line từ một center.
- Reservation nhiều line từ nhiều center.
- Không split một line item qua nhiều center.
- All-or-nothing rollback khi line cuối không đủ stock.
- Partial order ship được line có hàng và backorder/dead-letter đúng line thiếu.
- Company capacity tính đúng sau reservation và restock.
- Stock không bao giờ âm.
- Revenue dùng đúng unit price của center đã ship.

### 10.3 Backorder và business rules

- Order thiếu hàng được enqueue.
- RESTOCK trigger retry và ship được order đủ điều kiện.
- Backorder priority được xử lý trước standard.
- Standard quá 90 simulated seconds escalate đúng một lần.
- Tie-break cùng thời điểm là deterministic.
- Escalation không làm mất hoặc xử lý trùng order.
- Order vượt tổng company capacity được dead-letter ngay.
- Dead-letter line không revive sau RESTOCK theo quyết định đã chọn.

### 10.4 Console và lifecycle

- `STATUS` phản ánh snapshot nhất quán.
- `RESTOCK` reject SKU/center không hợp lệ.
- `RESTOCK` reject quantity 0 hoặc âm.
- `REPORT` tính đúng success rate và revenue.
- `AUDIT` trả đúng order events.
- Unknown command không làm crash process.
- `EXIT` chờ in-flight reservation và in final report.

### 10.5 Concurrency stress test

Harness phải:

- Spawn ít nhất 8 order-submission threads.
- Submit ít nhất 5.000 orders.
- Tổng demand của SKU mục tiêu ít nhất 150% starting stock.
- Giảm time-scale để có ít nhất một escalation trong vòng 30 giây.
- Kiểm tra tổng reserved units không vượt stock thực tế.
- Kiểm tra mỗi order chỉ được reserve tối đa một lần.
- Watchdog phát hiện thread không progress.
- Kiểm tra mỗi escalation xảy ra đúng một lần.
- In rõ `PASS` hoặc `FAIL` cho từng invariant và tổng thời gian chạy.
- Lưu output thật vào `STRESS_TEST_OUTPUT.txt`, không tự tạo hoặc chỉnh sửa kết quả.

### 10.6 Executable regression tests

`CoreBehaviorTest` chạy không cần framework và cover checksum/parser với continuation, reserved flag warning path, duplicate order rejection, unknown SKU rejection, malformed-header continuation, duplicate line atomic rollback, partial fulfillment, dead-letter, RESTOCK retry, concurrent duplicate submit và automatic escalation audit exactly once. `DomainSmokeTest` cover value-object validation. Cả hai test có exit code khác 0 khi assertion thất bại.

Regression suite cũng cover concurrent RESTOCK với snapshot không âm, shutdown trong lúc submitters còn chạy và partial order có nhiều line pending qua retry.

## 11. Deliverables sau khi implementation

- Java source theo package/module rõ ràng.
- `inventory_seed.txt`.
- `order_feed.txt` gồm valid và invalid records.
- `rejects.log` sinh từ lần chạy thực tế.
- `DESIGN.md` hoặc README hoàn chỉnh.
- `STRESS_TEST_OUTPUT.txt` từ một lần chạy thật.
- Phân tích output stress test 3-6 câu.
- Mục `Known Limitations / What I'd Do With More Time`.
- Mục `Bug I Found and Fixed`.
- Lệnh compile/run rõ ràng bằng JDK 17+.

## 12. Thứ tự triển khai sau khi review

1. Review và chốt tài liệu này.
2. Khởi tạo package structure và command-line scripts.
3. Viết domain model và immutable value objects.
4. Viết inventory seed reader và OFP parser.
5. Viết inventory repository và reservation engine.
6. Viết backorder, escalation, dead-letter và audit.
7. Viết console interface và graceful shutdown.
8. Viết test harness nhỏ theo từng module.
9. Viết stress test và chạy nhiều lần.
10. Chốt documentation, captured output và kiểm tra deliverables.

## 13. Bug I Found and Fixed

Trong lần chạy stress test đầu tiên, bộ đếm `submitted` tính cả các lần order được retry từ backorder queue. Vì vậy watchdog báo liveness thất bại dù toàn bộ producer thread đã hoàn tất. Nguyên nhân là metric submission được tăng ở mọi lần gọi `submit`, thay vì chỉ tăng lần đầu cho mỗi `OrderId`. Tôi đã sửa bằng một thread-safe set `acceptedOrderIds`, chỉ ghi nhận order đầu tiên; sau đó chạy lại stress test và liveness chuyển sang `PASS`.

## 14. Stress test result và phân tích

Output thực tế được lưu tại `STRESS_TEST_OUTPUT.txt`. Lần chạy đã dùng 8 threads, 5.000 orders, tổng demand 10.000 units trên starting stock 100 units và time-scale 1:1000.

Kết quả cho thấy reservation không vượt stock, không có duplicate reservation, không có stock âm và tất cả producer thread đều tiến triển. Kịch bản stock bị phân tán giữa các center đã tạo backorder có priority đang chờ, từ đó kích hoạt 4.899 standard-to-priority escalations. Số reservation thành công là 49 orders, tương đương 98 units; phần còn lại không được ship vì không có một center nào đủ 2 units hoặc đã vượt company capacity. Harness in `PASS` cho các invariant chính.

## 15. Known Limitations / What I'd Do With More Time

- Parser hiện phân đoạn logical order tuần tự trước khi phân phối block cho 4 worker; có thể cải tiến thành pipeline reader/parser worker nhưng vẫn phải giữ thứ tự continuation.
- `REPORT` và audit hiện là in-memory, chưa có persistence hoặc rotation cho log dài hạn.
- Partial fulfillment quản lý pending line ở mức order retry; chưa có màn hình chi tiết line-level cho console.
- Có thể bổ sung test runner duy nhất để chạy toàn bộ unit-style checks thay vì gọi từng class test riêng.
- Có thể thay `double` bằng integer cents để loại bỏ sai số floating-point khi tính revenue.
- Status observers chờ completion future theo từng reservation attempt, nên không đọc trạng thái cũ trong lúc inventory operation đang hoàn tất; inventory vẫn dùng fine-grained locks thay vì global lock.
- `shipped` được deduplicate theo `OrderId`, nên partial order retry không làm success rate tăng nhiều lần; revenue vẫn cộng theo allocation thực tế.
- Public `submit()` idempotent theo `OrderId`; concurrent duplicate submissions dùng chung completion result và chỉ một request được reservation.
- Partial lifecycle giữ pending lines trong backorder entry, dead-letter lines trong fulfillment result và phân biệt `PARTIALLY_SHIPPED` với `SHIPPED` khi retry hoàn tất.
- `OrderLine.lineNumber` được chuẩn hóa theo vị trí trong order, nên các line có cùng SKU và quantity vẫn được tracking độc lập.
- Latest `FulfillmentResult` được cập nhật sau mỗi retry; duplicate submit sau backorder nhận được trạng thái mới nhất.

## 16. Implementation status

- Đã hoàn thành domain, concurrent logical-block parser, inventory locking, atomic reservation, rollback, backorder, RESTOCK, escalation, dead-letter, audit, sample data và stress harness.
- Console đã hỗ trợ `STATUS`, `REPORT`, `RESTOCK`, `AUDIT`, `STRESS-TEST` và `EXIT` graceful với thời gian chờ ingestion worker.
- PDF đề bài chỉ nằm ở local và được loại khỏi Git bằng `.gitignore`.
