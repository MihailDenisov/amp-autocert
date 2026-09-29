package tech.maicraft.ampautocert;

import java.io.IOException;

/** Provider boundary for the exact DNS-01 TXT records owned by one ACME order. */
public interface DnsProvider {
    String createTxt(String recordName, String value) throws IOException, InterruptedException;
    void deleteTxt(String recordId) throws IOException, InterruptedException;
}
