package com.portfolio.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Digital Wallet Platform — portfolio MVP. A single modular application; see {@code
 * documentations/06. PortfolioMvpDecisions.md}.
 *
 * <p>The default in-memory user is excluded in {@code application.yaml} so that no generated
 * password is ever created or logged; authentication is added in PR 3.
 */
@SpringBootApplication
public class WalletApplication {

  public static void main(String[] args) {
    SpringApplication.run(WalletApplication.class, args);
  }
}
