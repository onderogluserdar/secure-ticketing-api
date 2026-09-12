package com.onderogluserdar.ticketing.security;

enum TokenType {
    ACCESS("access"),
    REFRESH("refresh");

    static final String CLAIM = "typ";

    private final String claimValue;

    TokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    String claimValue() {
        return claimValue;
    }
}
