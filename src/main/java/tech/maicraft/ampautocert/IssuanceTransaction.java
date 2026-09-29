package tech.maicraft.ampautocert;

import java.net.URI;
import java.util.List;
import java.util.Set;

/** Non-secret crash journal for one ACME order and only its Cloudflare-owned records. */
public record IssuanceTransaction(AcmeEnvironment environment, String transactionId,
                                  String zoneName, URI accountLocation, URI orderLocation, Phase phase,
                                  List<AcmeGateway.DnsChallenge> challenges, List<String> recordIds) {
    public IssuanceTransaction {
        if (environment == null || phase == null) throw new IllegalArgumentException("Issuance state is incomplete");
        if (transactionId == null || !transactionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid issuance transaction ID");
        }
        if (zoneName == null || !zoneName.matches("(?:[a-z0-9-]+\\.)+[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid issuance zone name");
        }
        accountLocation = validateResourceLocation(accountLocation, "/acme/acct/");
        orderLocation = validateResourceLocation(orderLocation, "/acme/order/");
        challenges = List.copyOf(challenges);
        recordIds = List.copyOf(recordIds);
        if (challenges.size() > 2 || challenges.stream().map(AcmeGateway.DnsChallenge::requestedDomain).distinct().count() != challenges.size()
                || challenges.stream().map(AcmeGateway.DnsChallenge::value).distinct().count() != challenges.size()) {
            throw new IllegalArgumentException("Invalid persisted DNS-01 challenge set");
        }
        for (AcmeGateway.DnsChallenge challenge : challenges) {
            if (!Set.of(zoneName, "*." + zoneName).contains(challenge.requestedDomain())
                    || !("_acme-challenge." + zoneName).equals(challenge.recordName())
                    || challenge.value() == null || !challenge.value().matches("[A-Za-z0-9_-]{20,255}")) {
                throw new IllegalArgumentException("Invalid persisted DNS-01 challenge");
            }
        }
        if (recordIds.size() > 2 || recordIds.stream().distinct().count() != recordIds.size()
                || recordIds.stream().anyMatch(id -> id == null || !id.matches("(?i)[0-9a-f]{32}"))) {
            throw new IllegalArgumentException("Invalid persisted Cloudflare record IDs");
        }
    }

    public String ownershipMarker() { return "amp-autocert:" + transactionId; }
    public IssuanceTransaction withPhase(Phase next) {
        return new IssuanceTransaction(environment, transactionId, zoneName, accountLocation, orderLocation, next, challenges, recordIds);
    }
    public IssuanceTransaction withRecordIds(List<String> ids) {
        return new IssuanceTransaction(environment, transactionId, zoneName, accountLocation, orderLocation, phase, challenges, ids);
    }

    private static URI validateResourceLocation(URI location, String pathPrefix) {
        if (location == null || !"https".equalsIgnoreCase(location.getScheme()) || location.getHost() == null
                || (location.getPort() != -1 && location.getPort() != 443) || location.getUserInfo() != null
                || location.getQuery() != null || location.getFragment() != null || location.getPath() == null
                || !location.getPath().startsWith(pathPrefix)) {
            throw new IllegalArgumentException("Invalid persisted ACME resource location");
        }
        return location.normalize();
    }

    public enum Phase { PREPARED, DNS_PUBLISHED, CHALLENGES_TRIGGERED, ORDER_READY, FINALIZED }
}
