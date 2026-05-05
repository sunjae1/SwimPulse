package com.swimpulse.notification;

public interface FcmClient {
	String send(FcmMessage message);
}
