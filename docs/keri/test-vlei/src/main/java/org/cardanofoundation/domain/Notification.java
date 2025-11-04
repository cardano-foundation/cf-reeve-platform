package org.cardanofoundation.domain;

public class Notification {
    public String i;
    public String dt;
    public boolean r;
    public NotificationAction a;

    public static class NotificationAction {
        public String r;
        public String d;
        public String m;
    }
}
