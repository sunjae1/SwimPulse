import {
  GoogleSignin,
  isSuccessResponse,
  statusCodes,
} from '@react-native-google-signin/google-signin';
import {mobileGoogleLogin} from '../api/client';
import type {AppUser} from '../api/types';
import {clearAccessToken, saveAccessToken} from './tokenStore';

const GOOGLE_WEB_CLIENT_ID =
  '829584550774-17n75ueed6o958h3078dljisuoi1g33r.apps.googleusercontent.com';
const ANDROID_PACKAGE_NAME = 'com.swimpulsemobile';
const DEBUG_SHA1 = '5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25';

let configured = false;

export function configureGoogleSignIn() {
  if (configured) {
    return;
  }

  GoogleSignin.configure({
    webClientId: GOOGLE_WEB_CLIENT_ID,
    offlineAccess: false,
  });
  configured = true;
}

export async function signInWithGoogle(): Promise<AppUser> {
  configureGoogleSignIn();
  let response;
  try {
    await GoogleSignin.hasPlayServices({showPlayServicesUpdateDialog: true});
    response = await GoogleSignin.signIn();
  } catch (error) {
    throw normalizeGoogleSignInError(error);
  }

  if (!isSuccessResponse(response)) {
    throw new Error('Google 로그인이 취소되었습니다.');
  }

  const idToken = response.data.idToken;
  if (!idToken) {
    throw new Error('Google ID token을 받지 못했습니다.');
  }

  const login = await mobileGoogleLogin(idToken);
  await saveAccessToken(login.accessToken);
  return login.user;
}

function normalizeGoogleSignInError(error: unknown) {
  const code = getErrorCode(error);

  if (code === statusCodes.SIGN_IN_CANCELLED) {
    return new Error('Google 로그인이 취소되었습니다.');
  }

  if (code === statusCodes.PLAY_SERVICES_NOT_AVAILABLE) {
    return new Error('Google Play Services를 사용할 수 없거나 업데이트가 필요합니다.');
  }

  if (code === 'DEVELOPER_ERROR') {
    return new Error(
      [
        'Google Android OAuth 설정이 앱 서명 정보와 맞지 않습니다.',
        `Google Cloud 또는 Firebase에 Android OAuth client를 추가하고 package name=${ANDROID_PACKAGE_NAME}, SHA-1=${DEBUG_SHA1}을 등록해 주세요.`,
        `idToken 발급용 webClientId=${GOOGLE_WEB_CLIENT_ID}도 현재 백엔드 Google client id와 같아야 합니다.`,
      ].join('\n'),
    );
  }

  return error instanceof Error ? error : new Error(String(error));
}

function getErrorCode(error: unknown) {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const code = (error as {code?: unknown}).code;
    return typeof code === 'string' ? code : null;
  }
  return null;
}

export async function signOutFromGoogle() {
  configureGoogleSignIn();
  await clearAccessToken();
  try {
    await GoogleSignin.signOut();
  } catch {
    // Local token cleanup is enough when Google SDK has no cached session.
  }
}
