package com.portfolio.wallet.audit.application;

import com.portfolio.wallet.audit.persistence.AuditLogRepository;
import com.portfolio.wallet.audit.persistence.AuditLogRow;
import com.portfolio.wallet.common.correlation.CorrelationId;
import java.net.InetAddress;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Writes the append-only audit log (documentations/03 §7; doc 07 §9.3). */
@Service
public class AuditService {

  private final AuditLogRepository repository;
  private final JsonMapper jsonMapper;

  public AuditService(AuditLogRepository repository, JsonMapper jsonMapper) {
    this.repository = repository;
    this.jsonMapper = jsonMapper;
  }

  /**
   * Records an action inside the caller's transaction, so the row commits or rolls back with the
   * action it describes (MVP-ADR-03). Fails if there is no transaction, because an audit row that
   * outlives a failed action would be false.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(AuditEntry entry) {
    repository.insert(toRow(entry));
  }

  /**
   * Records a failure in its own transaction, so the row survives the rollback of the caller's
   * transaction (doc 07 §9.3).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordIndependently(AuditEntry entry) {
    repository.insert(toRow(entry));
  }

  private AuditLogRow toRow(AuditEntry entry) {
    return new AuditLogRow(
        entry.actorId(),
        entry.actorType().name(),
        entry.action(),
        entry.resource(),
        entry.resourceId(),
        entry.result().name(),
        hostAddress(entry.clientIp()),
        CorrelationId.current().orElse(null),
        json(entry.oldValue()),
        json(entry.newValue()));
  }

  // Without an IPv6 zone ("%eth0"), which the inet column does not accept.
  private static String hostAddress(InetAddress address) {
    if (address == null) {
      return null;
    }
    String host = address.getHostAddress();
    int zone = host.indexOf('%');
    return zone < 0 ? host : host.substring(0, zone);
  }

  private String json(Map<String, Object> value) {
    return value == null ? null : jsonMapper.writeValueAsString(value);
  }
}
