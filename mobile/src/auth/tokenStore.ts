import * as Keychain from 'react-native-keychain';

const ACCESS_TOKEN_SERVICE = 'swimpulse.mobile.access-token';
const DEVICE_ID_SERVICE = 'swimpulse.mobile.device-id';

function createDeviceId() {
  const random = Math.random().toString(36).slice(2, 12);
  return `android-${Date.now().toString(36)}-${random}`;
}

export async function saveAccessToken(accessToken: string) {
  await Keychain.setGenericPassword('accessToken', accessToken, {
    service: ACCESS_TOKEN_SERVICE,
  });
}

export async function getAccessToken() {
  const credentials = await Keychain.getGenericPassword({
    service: ACCESS_TOKEN_SERVICE,
  });
  return credentials ? credentials.password : null;
}

export async function clearAccessToken() {
  await Keychain.resetGenericPassword({
    service: ACCESS_TOKEN_SERVICE,
  });
}

export async function getOrCreateDeviceId() {
  const credentials = await Keychain.getGenericPassword({
    service: DEVICE_ID_SERVICE,
  });

  if (credentials) {
    return credentials.password;
  }

  const deviceId = createDeviceId();
  await Keychain.setGenericPassword('deviceId', deviceId, {
    service: DEVICE_ID_SERVICE,
  });
  return deviceId;
}
