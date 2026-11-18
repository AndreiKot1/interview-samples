package com.example.orders;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderPaidListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidListener.class);

    private final OrderRepository orderRepository;
    private final ApiClient apiClient;

    public OrderPaidListener(OrderRepository orderRepository, ApiClient apiClient) {
        this.orderRepository = orderRepository;
        this.apiClient = apiClient;
    }

    public record OrderPaidEvent(long orderId, String paymentId, BigDecimal amount) {}

    @RabbitListener(queues = "order.paid")
    @Transactional
    public void onOrderPaid(OrderPaidEvent event) throws IOException, InterruptedException {
        log.info("Received order paid event for order {}", event.orderId());

        OrderEntity order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + event.orderId()));

        String shipmentId = apiClient.createShipment(order);
        apiClient.creditLoyaltyPoints(order);

        generateConfirmationPdf(order, shipmentId);

        order.setStatus("SHIPPED");
        orderRepository.save(order);

        log.info("Order {} shipped with shipment {}", order.getId(), shipmentId);
    }

    private void generateConfirmationPdf(OrderEntity order, String shipmentId) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(16f);
                content.newLineAtOffset(50, 750);
                content.showText("Order confirmation #" + order.getId());
                content.newLine();
                content.showText("Customer: " + order.getCustomerId());
                content.newLine();
                content.showText("Item: " + order.getSku() + " x " + order.getQuantity());
                content.newLine();
                content.showText("Amount: " + order.getAmount());
                content.newLine();
                content.showText("Shipment: " + shipmentId);
                content.endText();
            }

            Path target = Path.of("/data/confirmations", "order-" + order.getId() + ".pdf");
            Files.createDirectories(target.getParent());
            document.save(target.toFile());
        }
    }
}
