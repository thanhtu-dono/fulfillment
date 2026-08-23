package com.example.fulfillment.domain;

public enum FulfillmentCenter {
    FC_EAST("FC-EAST"),
    FC_WEST("FC-WEST"),
    FC_CENTRAL("FC-CENTRAL");

    private final String code;

    FulfillmentCenter(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static FulfillmentCenter fromCode(String value) {
        for (FulfillmentCenter center : values()) {
            if (center.code.equals(value)) {
                return center;
            }
        }
        throw new IllegalArgumentException("Unknown fulfillment center: " + value);
    }

    @Override
    public String toString() {
        return code;
    }
}
