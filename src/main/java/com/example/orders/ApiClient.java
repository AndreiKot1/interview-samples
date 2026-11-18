package com.example.orders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

@Component
public class ApiClient {

    private static final String INVENTORY_URL = "https://inventory.example.com/api/v1/reservations";
    private static final String PAYMENT_URL = "https://payments.example.com/api/v1/charges";
    private static final String MAIL_URL = "https://mail.example.com/api/v1/send";
    private static final String SHIPPING_URL = "https://shipping.example.com/api/v1/shipments";
    private static final String LOYALTY_URL = "https://loyalty.example.com/api/v1/points";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void reserveStock(OrderEntity order) throws IOException, InterruptedException {
        HttpResponse<String> response = post(INVENTORY_URL, String.format(
                "{\"orderId\":%d,\"sku\":\"%s\",\"quantity\":%d}", order.getId(), order.getSku(), order.getQuantity()));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Stock reservation failed: " + response.statusCode());
        }
    }

    public String chargeCustomer(OrderEntity order) throws IOException, InterruptedException {
        HttpResponse<String> response = post(PAYMENT_URL, String.format(
                "{\"orderId\":%d,\"customerId\":%d,\"amount\":%s}",
                order.getId(), order.getCustomerId(), order.getAmount()));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Payment failed: " + response.statusCode());
        }
        return response.body();
    }

    public void sendConfirmationMail(OrderEntity order) throws IOException, InterruptedException {
        post(MAIL_URL, String.format(
                "{\"to\":\"%s\",\"template\":\"order-confirmed\",\"orderId\":%d}", order.getEmail(), order.getId()));
    }

    public String createShipment(OrderEntity order) throws IOException, InterruptedException {
        HttpResponse<String> response = post(SHIPPING_URL, String.format(
                "{\"orderId\":%d,\"sku\":\"%s\",\"quantity\":%d}", order.getId(), order.getSku(), order.getQuantity()));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Shipment creation failed: " + response.statusCode());
        }
        return response.body();
    }

    public void creditLoyaltyPoints(OrderEntity order) throws IOException, InterruptedException {
        HttpResponse<String> response = post(LOYALTY_URL, String.format(
                "{\"customerId\":%d,\"points\":%s}", order.getCustomerId(), order.getAmount()));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Loyalty credit failed: " + response.statusCode());
        }
    }

    private HttpResponse<String> post(String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
