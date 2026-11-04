package com.portfolio.wallet.common.time;

import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The business calendar shared by every module: {@code app.business-zone}, Asia/Bangkok in the MVP
 * (documentations/06 MVP-ADR-22). It decides what "today" is (the date-of-birth check, and the
 * daily-limit day in PR 4b) and the zone in which emails state the time of a change (MVP-ADR-25).
 * Instants are stored in UTC; only their presentation and the calendar day use this zone.
 *
 * @param businessZone the business time zone
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record BusinessTimeProperties(@NotNull ZoneId businessZone) {}
