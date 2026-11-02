package com.portfolio.wallet.auth.web;

/** Identical for a new and an existing account (documentations/06 MVP-ADR-23). */
record RegisterResponse(String email, String status) {}
