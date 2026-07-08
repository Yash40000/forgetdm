package io.forgetdm.mainframe.transport;

import io.forgetdm.mainframe.MainframeConnectionEntity;

import java.util.List;

/**
 * Abstraction over "get bytes off an LPAR / put bytes back". Implemented by a LOCAL landing-folder
 * provider (runnable today) and a ZOWE/z-OSMF provider (real mainframe, same interface).
 */
public interface MainframeTransport {

    /** Dataset / file metadata as the source reports it. */
    record RemoteFile(String name, String recfm, Integer lrecl, Long sizeBytes, String dsorg) {}

    /** List datasets/files matching a pattern (e.g. {@code HLQ.TEST.*} or a glob on a folder). */
    List<RemoteFile> list(MainframeConnectionEntity conn, String pattern);

    /** Attributes of a single dataset/file (RECFM, LRECL, size). */
    RemoteFile stat(MainframeConnectionEntity conn, String name);

    /** Raw bytes of a dataset/file (no code-page translation — binary). */
    byte[] fetch(MainframeConnectionEntity conn, String name);

    /** Write raw bytes to a dataset/file on the target, carrying the intended RECFM/LRECL. */
    void put(MainframeConnectionEntity conn, String name, byte[] data, String recfm, Integer lrecl);
}
