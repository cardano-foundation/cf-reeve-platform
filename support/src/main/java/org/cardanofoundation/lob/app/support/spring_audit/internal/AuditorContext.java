package org.cardanofoundation.lob.app.support.spring_audit.internal;

import java.util.Optional;

public class AuditorContext {

    private static final ThreadLocal<String> currentUser = new ThreadLocal<>();

    public static void setCurrentUser(String user) {
        currentUser.set(user);
    }

    public static Optional<String> getCurrentUser() {
        return Optional.ofNullable(currentUser.get());
    }

    public static void clear() {
        currentUser.remove();
    }

}
