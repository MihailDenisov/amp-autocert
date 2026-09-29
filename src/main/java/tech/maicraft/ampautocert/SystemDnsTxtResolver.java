package tech.maicraft.ampautocert;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.NameNotFoundException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded public DNS TXT lookup used only by the background renewal worker. */
final class SystemDnsTxtResolver implements DnsTxtResolver {
    private final Clock clock;
    private final Duration maximumLookup;

    SystemDnsTxtResolver(Clock clock, Duration maximumLookup) {
        if (maximumLookup.isZero() || maximumLookup.isNegative()) {
            throw new IllegalArgumentException("DNS lookup timeout must be positive");
        }
        this.clock = clock;
        this.maximumLookup = maximumLookup;
    }

    @Override
    public Set<String> resolve(String recordName, Instant deadline) throws IOException, InterruptedException {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) throw new IOException("DNS lookup deadline expired");
        Duration timeout = remaining.compareTo(maximumLookup) < 0 ? remaining : maximumLookup;
        long timeoutMillis = Math.max(1L, timeout.toMillis());
        FutureTask<Set<String>> lookup = new FutureTask<>(() -> query(recordName, timeoutMillis));
        Thread worker = Thread.ofVirtual().name("amp-autocert-dns-lookup").start(lookup);
        try {
            return lookup.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            lookup.cancel(true);
            worker.interrupt();
            throw new IOException("DNS TXT lookup timed out");
        } catch (InterruptedException interrupted) {
            lookup.cancel(true);
            worker.interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("DNS TXT lookup failed");
        }
    }

    private static Set<String> query(String recordName, long timeoutMillis) throws IOException {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", Long.toString(timeoutMillis));
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        InitialDirContext context = null;
        try {
            context = new InitialDirContext(environment);
            Attributes attributes = context.getAttributes(recordName, new String[]{"TXT"});
            Attribute txt = attributes.get("TXT");
            if (txt == null) return Set.of();
            NamingEnumeration<?> all = txt.getAll();
            try {
                while (all.hasMore()) values.add(decodeTxt(String.valueOf(all.next())));
            } finally {
                all.close();
            }
            return Set.copyOf(values);
        } catch (NameNotFoundException notPublishedYet) {
            return Set.of();
        } catch (NamingException failure) {
            throw new IOException("DNS TXT lookup failed");
        } finally {
            if (context != null) {
                try { context.close(); } catch (NamingException ignored) { }
            }
        }
    }

    /** JNDI renders a multi-string TXT RR as adjacent quoted chunks. */
    static String decodeTxt(String rendered) throws IOException {
        String value = rendered.trim();
        if (!value.startsWith("\"")) return value;
        StringBuilder decoded = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            if (index >= value.length()) break;
            if (value.charAt(index++) != '"') throw new IOException("Malformed DNS TXT response");
            boolean closed = false;
            while (index < value.length()) {
                char current = value.charAt(index++);
                if (current == '"') {
                    closed = true;
                    break;
                }
                if (current == '\\') {
                    if (index >= value.length()) throw new IOException("Malformed DNS TXT response");
                    current = value.charAt(index++);
                }
                decoded.append(current);
            }
            if (!closed) throw new IOException("Malformed DNS TXT response");
        }
        return decoded.toString();
    }
}
