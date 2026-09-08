# BÀI TẬP 3: CHUYỂN ĐỔI TỪ SOA SANG MICROSERVICE ARCHITECTURE BẰNG REST API
**Học phần:** Kiến trúc phần mềm phân tán / Microservice Architecture  
**Session 02:** Từ Monolithic đến Microservice  
**Hệ thống thực nghiệm:** LibraX (Hệ thống quản lý thư viện số)

---

## 1. Phân tích vấn đề kỹ thuật của đoạn mã ban đầu

Đoạn code ban đầu của `borrowing-service`:
```java
@Service
public class BookClientService {

    private RestTemplate restTemplate = new RestTemplate();

    public String getBookTitle(Long bookId) {
        String url = "http://192.168.1.15:8082/api/books/" + bookId;
        return restTemplate.getForObject(url, String.class);
    }
}
```

### Các điểm hạn chế & nguy cơ trong môi trường phân tán:
1. **Hardcoded IP & Port (`192.168.1.15:8082`):**
   - Trong môi trường containerized/cloud (Kubernetes, Docker Swarm, Eureka/Consul), các instance của `book-service` có tính chất tạm thời (ephemeral). Khi autoscaling, restart hoặc rolling update, địa chỉ IP và port nội bộ sẽ thay đổi liên tục. Việc fix cứng IP khiến request trỏ vào instance đã chết hoặc không tồn tại, gây lỗi kết nối (`Connection Refused` / `ConnectTimeoutException`).
2. **Thiếu cơ chế Client-side Load Balancing:**
   - Ngay cả khi IP `192.168.1.15` còn sống, toàn bộ tải request từ `borrowing-service` đều dồn vào đúng một instance này, trong khi các instance khác của `book-service` hoàn toàn nhàn rỗi (idle). Điều này làm mất đi hoàn toàn lợi ích của việc scale ngang (horizontal scaling).
3. **Không tích hợp Service Discovery:**
   - Client không biết được trạng thái sống/chết (liveness/health check) của downstream service, không thể tự động nhận biết instance mới xuất hiện hoặc instance bị loại bỏ.
4. **Thiếu khả năng chịu lỗi (Resilience & Fault Tolerance):**
   - Không có timeout cấu hình, không có cơ chế Retry hoặc Circuit Breaker (Resilience4j). Nếu `book-service` phản hồi chậm hoặc sập, các luồng (threads) của `borrowing-service` sẽ bị treo và cạn kiệt (thread starvation), dẫn đến sự cố sụp đổ dây chuyền (cascading failure).

---

## 2. Giải pháp tái cấu trúc với Service Discovery & `@LoadBalanced RestTemplate`

Để giải quyết triệt để vấn đề trên trong hệ sinh thái Spring Cloud (Eureka / Consul / K8s DNS), chúng ta áp dụng mô hình:
1. Đăng ký bean `RestTemplate` có gắn annotation `@LoadBalanced` để kích hoạt bộ cân bằng tải phía client (Spring Cloud LoadBalancer).
2. Thay thế địa chỉ IP tĩnh bằng tên logic (Application Name) đã đăng ký trên Service Registry: `http://book-service/...`.
3. Bổ sung cấu hình Timeout và xử lý ngoại lệ / fallback cơ bản.

### 2.1. Cấu hình Configuration Bean (`WebClientConfig.java` / `RestTemplateConfig.java`)

```java
package com.librax.borrowing.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced // Kích hoạt Spring Cloud LoadBalancer để phân giải tên service thành IP thực tế
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Cấu hình Timeout tránh treo tài nguyên khi downstream service nghẽn
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        return new RestTemplate(factory);
    }
}
```

### 2.2. Triển khai Service (`BookClientService.java`)

```java
package com.librax.borrowing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class BookClientService {

    private static final Logger log = LoggerFactory.getLogger(BookClientService.class);
    private static final String BOOK_SERVICE_NAME = "book-service";

    private final RestTemplate restTemplate;

    @Autowired
    public BookClientService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String getBookTitle(Long bookId) {
        // Sử dụng tên định danh logic "book-service" thay cho IP:PORT cứng
        String url = String.format("http://%s/api/books/%d", BOOK_SERVICE_NAME, bookId);
        
        try {
            log.info("Đang gửi yêu cầu lấy tiêu đề sách [ID={}] qua LoadBalanced RestTemplate tới: {}", bookId, url);
            return restTemplate.getForObject(url, String.class);
        } catch (RestClientException ex) {
            log.error("Lỗi khi giao tiếp với book-service (bookId={}): {}", bookId, ex.getMessage());
            // Chiến lược Fallback khi dịch vụ hạ tầng gặp sự cố hoặc timeout
            return getDefaultBookTitle(bookId);
        }
    }

    private String getDefaultBookTitle(Long bookId) {
        return "Thông tin sách tạm thời không khả dụng (ID: " + bookId + ")";
    }
}
```

---

## 3. Phân tích so sánh: Chuyển đổi từ SOA sang MSA tại LibraX

### Bối cảnh hệ thống LibraX
Hệ thống thư viện LibraX gồm nhiều nghiệp vụ có tính chất tải khác nhau: quản lý mượn trả (`borrowing-service`), tra cứu danh mục sách (`book-service`), quản lý độc giả (`member-service`) và phạt trễ hạn (`penalty-service`). Ban đầu, kiến trúc SOA sử dụng trục tích hợp Enterprise Service Bus (ESB) tập trung để kết nối các service qua giao thức chuẩn hóa SOAP/XML. Khi chuyển sang Microservice Architecture (MSA) dựa trên REST API nhẹ, hệ thống có những đánh đổi căn bản sau:

### 1. Tốc độ phát triển & phát hành (Development & Delivery Velocity)
* **SOA (ESB):** Tốc độ phát triển thường bị nghẽn (bottleneck) tại tầng ESB. Bất kỳ thay đổi schema hay thêm trường dữ liệu nào giữa `book-service` và `borrowing-service` đều đòi hỏi phối hợp với đội quản trị ESB để định tuyến và chuyển đổi dữ liệu (data transformation/mediation).
* **MSA (REST API):** Đội ngũ phát triển `borrowing-service` và `book-service` làm việc độc lập hoàn toàn theo mô hình phân quyền chéo (cross-functional teams). Giao tiếp REST qua JSON đơn giản, chuẩn hóa, hỗ trợ ký kết hợp đồng rõ ràng (API Contracts/OpenAPI). Nhờ đó, CI/CD được tự động hóa triệt để, chu kỳ release tính năng mượn sách mới rút ngắn từ hàng tháng xuống còn vài ngày.

### 2. Độ phức tạp vận hành (Operational Complexity)
* **SOA:** Độ phức tạp tập trung vào việc duy trì, giám sát khối ESB khổng lồ. Mặc dù ESB phức tạp, nhưng điểm kết nối là tập trung (centralized management) nên dễ kiểm soát luồng thông điệp qua một đầu mối.
* **MSA:** Sự dịch chuyển sang "Smart endpoints, dumb pipes" đẩy toàn bộ gánh nặng điều phối và định tuyến về phía hạ tầng biên và ứng dụng. LibraX buộc phải vận hành thêm một loạt thành phần phân tán: Service Registry (Eureka/Consul), API Gateway, Distributed Tracing (Zipkin/Jaeger) và cấu hình phân giải tải phía client. Vấn đề "bất định địa chỉ IP" như tình huống thực tế của bài tập chính là minh chứng rõ nét cho sự gia tăng đột biến về độ phức tạp khi vận hành hàng chục container/pod biến động liên tục.

### 3. Khả năng chịu lỗi & độ khả dụng (Fault Tolerance & Reliability)
* **SOA:** ESB đóng vai trò là "Single Point of Failure" (SPOF). Khi trục tích hợp ESB quá tải hoặc gặp lỗi runtime, toàn bộ giao tiếp nội bộ giữa các phân hệ của LibraX đều tê liệt hoàn toàn.
* **MSA:** MSA loại bỏ hoàn toàn SPOF trung tâm. Một lỗi sập của `book-service` hoặc nghẽn mạng cục bộ có thể được cách ly hiệu quả bằng mô hình Circuit Breaker, Bulkhead, Fallback và cân bằng tải động (`@LoadBalanced`). `borrowing-service` vẫn duy trì hoạt động và ghi nhận yêu cầu mượn offline thay vì sập toàn bộ hệ thống. Tuy nhiên, tính chịu lỗi của MSA đòi hỏi kỹ năng thiết kế cao hơn nhiều, bởi nếu không xử lý timeout và fallback kỹ lưỡng, hệ thống sẽ rất dễ dính lỗi sụp đổ dây chuyền (cascading failures).