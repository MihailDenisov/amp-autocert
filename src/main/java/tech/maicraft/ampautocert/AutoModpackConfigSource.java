package tech.maicraft.ampautocert;

import java.io.IOException;

/** Narrow, version-pinned boundary around AutoModpack's loaded and on-disk server configuration. */
interface AutoModpackConfigSource {
    AutoModpackIdentitySnapshot effective(AutoCertConfig config) throws IOException;
    AutoModpackIdentitySnapshot onDisk(AutoCertConfig config) throws IOException;
}
