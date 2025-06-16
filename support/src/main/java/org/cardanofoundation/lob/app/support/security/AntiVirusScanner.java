package org.cardanofoundation.lob.app.support.security;

public interface AntiVirusScanner {

    boolean isFileSafe(byte[] fileBytes);
}
