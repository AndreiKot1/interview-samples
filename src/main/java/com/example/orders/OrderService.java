package com.example.orders;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String PAYMENT_URL = "https://payments.example.com/api/v1/charges";
    private static final String INVENTORY_URL = "https://inventory.example.com/api/v1/reservations";
    private static final String MAIL_URL = "https://mail.example.com/api/v1/send";

    private final OrderRepository orderRepository;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public record PlaceOrderRequest(long customerId, String sku, int quantity, BigDecimal amount, String email) {}

    @Transactional
    public long placeOrder(PlaceOrderRequest request) throws IOException, InterruptedException {
        OrderEntity order = orderRepository.saveAndFlush(
                new OrderEntity(request.customerId(), request.sku(), request.quantity(), request.amount(), request.email()));

        reserveStock(order);

        String paymentId = chargeCustomer(order);
        order.setPaymentId(paymentId);
        order.setStatus("PAID");
        orderRepository.save(order);

        sendConfirmationMail(order);

        if (order.getQuantity() > 100) {
            throw new IllegalStateException("Bulk orders need manual approval");
        }

        return order.getId();
    }

    public void retryFailedOrders() throws Exception {
        List<OrderEntity> failedOrders = orderRepository.findByStatus("NEW");
        for (OrderEntity failed : failedOrders) {
            PlaceOrderRequest request = new PlaceOrderRequest(
                    failed.getCustomerId(), failed.getSku(), failed.getQuantity(), failed.getAmount(), failed.getEmail());
            try {
                placeOrder(request);
            } catch (Exception e) {
                log.warn("Retry for order {} failed", failed.getId(), e);
            }
        }
    }

    private void reserveStock(OrderEntity order) throws IOException, InterruptedException {
        String body = String.format(
                "{\"orderId\":%d,\"sku\":\"%s\",\"quantity\":%d}", order.getId(), order.getSku(), order.getQuantity());
        HttpResponse<String> response = post(INVENTORY_URL, body);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Stock reservation failed: " + response.statusCode());
        }
    }

    private String chargeCustomer(OrderEntity order) throws IOException, InterruptedException {
        String body = String.format(
                "{\"orderId\":%d,\"customerId\":%d,\"amount\":%s}", order.getId(), order.getCustomerId(), order.getAmount());
        HttpResponse<String> response = post(PAYMENT_URL, body);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Payment failed: " + response.statusCode());
        }
        return response.body();
    }

    private void sendConfirmationMail(OrderEntity order) {
        try {
            post(MAIL_URL, String.format(
                    "{\"to\":\"%s\",\"template\":\"order-confirmed\",\"orderId\":%d}", order.getEmail(), order.getId()));
        } catch (Exception e) {
            log.warn("Could not send mail for order {}", order.getId());
        }
    }

    private HttpResponse<String> post(String url, String body) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }
}
