package tech.maicraft.ampautocert;

import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.Session;

import static org.junit.jupiter.api.Assertions.*;

class Acme4jDependencyTest {
    @Test void acmeAndCryptoRuntimeClassesAreResolvable() {
        assertDoesNotThrow(() -> new Session("https://acme-staging-v02.api.letsencrypt.org/directory"));
        assertDoesNotThrow(() -> Class.forName("org.jose4j.jws.JsonWebSignature"));
        assertDoesNotThrow(() -> Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider"));
        assertDoesNotThrow(() -> Class.forName("org.bouncycastle.cert.X509CertificateHolder"));
    }
}
