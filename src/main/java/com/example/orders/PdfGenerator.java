package com.example.orders;

import java.io.IOException;

public interface PdfGenerator {

    void generateConfirmation(OrderEntity order, String shipmentId) throws IOException;
}
