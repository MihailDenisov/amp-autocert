package tech.maicraft.ampautocert;

import java.io.IOException;
import java.util.List;

/** DNS provider operations that make an interrupted create rediscoverable by a unique ownership marker. */
public interface RecoverableDnsProvider extends DnsProvider {
    String createOwnedTxt(String recordName, String value, String ownershipMarker)
            throws IOException, InterruptedException;
    List<String> findOwnedTxt(String recordName, String ownershipMarker)
            throws IOException, InterruptedException;
}
