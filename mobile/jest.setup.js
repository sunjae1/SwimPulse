/* eslint-env jest */

jest.mock('react-native-keychain', () => ({
  setGenericPassword: jest.fn(() => Promise.resolve(true)),
  getGenericPassword: jest.fn(() => Promise.resolve(false)),
  resetGenericPassword: jest.fn(() => Promise.resolve(true)),
}));

jest.mock('@react-native-google-signin/google-signin', () => ({
  GoogleSignin: {
    configure: jest.fn(),
    hasPlayServices: jest.fn(() => Promise.resolve(true)),
    signIn: jest.fn(() =>
      Promise.resolve({
        type: 'success',
        data: {idToken: 'test-id-token'},
      }),
    ),
    signOut: jest.fn(() => Promise.resolve(null)),
  },
  isSuccessResponse: response => response.type === 'success',
}));

jest.mock('@react-native-firebase/messaging', () => {
  const messaging = jest.fn(() => ({
    registerDeviceForRemoteMessages: jest.fn(() => Promise.resolve()),
    requestPermission: jest.fn(() => Promise.resolve(1)),
    getToken: jest.fn(() => Promise.resolve('test-fcm-token')),
    deleteToken: jest.fn(() => Promise.resolve()),
    onMessage: jest.fn(() => jest.fn()),
    onNotificationOpenedApp: jest.fn(() => jest.fn()),
    getInitialNotification: jest.fn(() => Promise.resolve(null)),
  }));
  return messaging;
});

jest.mock('react-native-geolocation-service', () => ({
  getCurrentPosition: jest.fn(),
}));

global.fetch = jest.fn(() =>
  Promise.resolve({
    ok: true,
    status: 200,
    text: () =>
      Promise.resolve(
        JSON.stringify({
          id: 1,
          email: 'mobile@example.com',
          displayName: 'Mobile Test',
          profileImageUrl: null,
          notificationEnabled: true,
          role: 'USER',
          fcmTokenRegistered: false,
          createdAt: new Date().toISOString(),
          lastLoginAt: null,
        }),
      ),
  }),
);
