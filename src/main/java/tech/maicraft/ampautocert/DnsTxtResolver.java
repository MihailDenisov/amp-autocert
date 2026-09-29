package tech.maicraft.ampautocert;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

@FunctionalInterface
public interface DnsTxtResolver {
    Set<String> resolve(String recordName, Instant deadline) throws IOException, InterruptedException;
}
