package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SystemDnsTxtResolverTest {
    @Test void decodesSingleAndChunkedJndiTxtValues() throws Exception {
        assertEquals("challenge", SystemDnsTxtResolver.decodeTxt("\"challenge\""));
        assertEquals("challenge-value", SystemDnsTxtResolver.decodeTxt("\"challenge\" \"-value\""));
        assertEquals("plain", SystemDnsTxtResolver.decodeTxt("plain"));
        assertEquals("a\"b", SystemDnsTxtResolver.decodeTxt("\"a\\\"b\""));
    }

    @Test void rejectsMalformedQuotedTxtValues() {
        assertThrows(IOException.class, () -> SystemDnsTxtResolver.decodeTxt("\"unterminated"));
        assertThrows(IOException.class, () -> SystemDnsTxtResolver.decodeTxt("\"ok\" garbage"));
    }
}
