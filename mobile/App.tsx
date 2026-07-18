import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {
  ActivityIndicator,
  Alert,
  Linking,
  Modal,
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
  useColorScheme,
} from 'react-native';
import Geolocation from 'react-native-geolocation-service';
import {createNavigationContainerRef, NavigationContainer, useFocusEffect} from '@react-navigation/native';
import {createNativeStackNavigator} from '@react-navigation/native-stack';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {
  ApiError,
  API_BASE_URL,
  createPoolFromLocationCandidate,
  confirmSubscriptionSourceReview,
  createSubscription,
  deleteSubscription,
  getEvents,
  getMe,
  getMyPage,
  getNearbyPools,
  getNotification,
  getNotificationPage,
  getPools,
  getSubscriptions,
  health,
  markNotificationRead,
  scanPoolNotices,
  searchLocations,
  updateSubscriptionPeriod,
} from './src/api/client';
import type {
  AppUser,
  InAppNotification,
  LocationSearchCandidate,
  MyPageData,
  NearbyPool,
  NoticeRegistrationPeriod,
  NoticeScanResponse,
  Pool,
  PoolNotice,
  PoolLocationCandidate,
  RegistrationEvent,
  Subscription,
} from './src/api/types';
import {signInWithGoogle, signOutFromGoogle} from './src/auth/googleAuth';
import {
  getInitialPushMessage,
  registerPushToken,
  sendMobileTestNotification,
  subscribeToForegroundPushMessages,
  subscribeToOpenedPushMessages,
  unregisterPushToken,
} from './src/notifications/push';
import type {ReceivedPushMessage} from './src/notifications/push';
import {
  eventStatusLabel,
  formatDateTime,
  formatShortPeriod,
  fromInputDateTime,
  isEventClosed,
  isOcrInProgress,
  isPastPeriod,
  shiftPeriodToCurrentMonth,
  subscriptionKey,
  subscriptionKeyFromEvent,
  subscriptionTitle,
  toInputDateTime,
} from './src/utils/date';

type DateTimeParts = {
  date: string;
  hour: string;
  minute: string;
};

const POOL_PAGE_SIZE = 10;

type RootStackParamList = {
  Home: undefined;
  MyPage: {subscriptionId?: number} | undefined;
  Settings: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();
const navigationRef = createNavigationContainerRef<RootStackParamList>();

type AppState = {
  user: AppUser | null;
  setUser: (user: AppUser | null) => void;
};

function App() {
  const isDarkMode = useColorScheme() === 'dark';
  const [user, setUser] = useState<AppUser | null>(null);
  const [booting, setBooting] = useState(true);
  const [pushNotification, setPushNotification] = useState<InAppNotification | null>(null);
  const [pushFallback, setPushFallback] = useState<ReceivedPushMessage | null>(null);
  const [pushLoading, setPushLoading] = useState(false);

  useEffect(() => {
    getMe()
      .then(setUser)
      .catch(() => setUser(null))
      .finally(() => setBooting(false));
  }, []);

  const openPushNotification = useCallback(async (message: ReceivedPushMessage) => {
    const notificationId = Number(message.notificationId);
    setPushFallback(message);

    if (!Number.isInteger(notificationId) || notificationId <= 0) {
      setPushNotification(null);
      return;
    }

    try {
      setPushLoading(true);
      setPushNotification(await getNotification(notificationId));
    } catch {
      setPushNotification(null);
    } finally {
      setPushLoading(false);
    }
  }, []);

  const closePushNotification = useCallback(async () => {
    const notification = pushNotification;
    setPushNotification(null);
    setPushFallback(null);

    if (notification && !notification.readAt) {
      try {
        await markNotificationRead(notification.id);
      } catch (error) {
        showError('알림 읽음 처리에 실패했습니다.', error);
      }
    }
  }, [pushNotification]);

  useEffect(() => {
    return subscribeToForegroundPushMessages(openPushNotification);
  }, [openPushNotification]);

  useEffect(() => {
    return subscribeToOpenedPushMessages(openPushNotification);
  }, [openPushNotification]);

  useEffect(() => {
    getInitialPushMessage()
      .then(message => {
        if (message) {
          openPushNotification(message);
        }
      })
      .catch(() => undefined);
  }, [openPushNotification]);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <NavigationContainer ref={navigationRef}>
        <Stack.Navigator
          screenOptions={{
            headerStyle: {backgroundColor: '#f7fbff'},
            headerTitleStyle: {fontWeight: '800'},
            headerTintColor: '#0f3554',
            contentStyle: {backgroundColor: '#eef7fb'},
          }}>
          <Stack.Screen name="Home" options={{title: 'SwimPulse'}}>
            {props => (
              <HomeScreen
                {...props}
                user={user}
                setUser={setUser}
                booting={booting}
              />
            )}
          </Stack.Screen>
          <Stack.Screen name="MyPage" options={{title: '마이페이지'}}>
            {props => <MyPageScreen {...props} user={user} setUser={setUser} />}
          </Stack.Screen>
          <Stack.Screen name="Settings" options={{title: '설정'}}>
            {props => <SettingsScreen {...props} user={user} setUser={setUser} />}
          </Stack.Screen>
        </Stack.Navigator>
        <PushNotificationModal
          notification={pushNotification}
          fallback={pushFallback}
          loading={pushLoading}
          onClose={closePushNotification}
          onReview={subscriptionId => {
            closePushNotification().catch(() => undefined);
            if (navigationRef.isReady()) {
              navigationRef.navigate('MyPage', {subscriptionId});
            }
          }}
        />
      </NavigationContainer>
    </SafeAreaProvider>
  );
}

function HomeScreen({
  navigation,
  user,
  setUser,
  booting,
}: AppState & {navigation: {navigate: (screen: keyof RootStackParamList) => void}; booting: boolean}) {
  const [pools, setPools] = useState<Pool[]>([]);
  const [events, setEvents] = useState<RegistrationEvent[]>([]);
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [nearbyPools, setNearbyPools] = useState<NearbyPool[]>([]);
  const [locationQuery, setLocationQuery] = useState('');
  const [locationResults, setLocationResults] = useState<LocationSearchCandidate[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyMessage, setBusyMessage] = useState<string | null>(null);
  const [scanResult, setScanResult] = useState<NoticeScanResponse | null>(null);
  const [scanVisible, setScanVisible] = useState(false);
  const [poolPage, setPoolPage] = useState(1);

  const subscribedKeys = useMemo(() => {
    return new Set(subscriptions.map(subscriptionKey));
  }, [subscriptions]);
  const poolTotalPages = Math.max(1, Math.ceil(pools.length / POOL_PAGE_SIZE));
  const safePoolPage = Math.min(poolPage, poolTotalPages);
  const visiblePools = useMemo(() => {
    const startIndex = (safePoolPage - 1) * POOL_PAGE_SIZE;
    return pools.slice(startIndex, startIndex + POOL_PAGE_SIZE);
  }, [safePoolPage, pools]);

  const loadDashboard = useCallback(async () => {
    setLoading(true);
    try {
      const [poolResult, eventResult, subscriptionResult] = await Promise.all([
        getPools(),
        getEvents(),
        user ? getSubscriptions() : Promise.resolve([] as Subscription[]),
      ]);
      setPools(poolResult);
      setPoolPage(1);
      setEvents(eventResult);
      setSubscriptions(subscriptionResult);
    } catch (error) {
      showError('홈 데이터를 불러오지 못했습니다.', error);
    } finally {
      setLoading(false);
    }
  }, [user]);

  useEffect(() => {
    loadDashboard();
  }, [loadDashboard]);

  async function login() {
    try {
      setBusyMessage('Google 로그인 중입니다.');
      const signedInUser = await signInWithGoogle();
      setUser(signedInUser);
    } catch (error) {
      showError('Google 로그인에 실패했습니다.', error);
    } finally {
      setBusyMessage(null);
    }
  }

  async function openNoticeScan(pool: Pool) {
    try {
      setBusyMessage(`${pool.name} 공지를 확인하고 있습니다.`);
      const result = await scanPoolNotices(pool.id);
      setScanResult(result);
      setScanVisible(true);
    } catch (error) {
      showError('공지 확인에 실패했습니다.', error);
    } finally {
      setBusyMessage(null);
    }
  }

  async function refreshSubscriptions() {
    if (!user) {
      setSubscriptions([]);
      return;
    }
    try {
      setSubscriptions(await getSubscriptions());
    } catch {
      // The next explicit reload will surface the error.
    }
  }

  async function searchByKeyword() {
    if (!locationQuery.trim()) {
      return;
    }
    try {
      setBusyMessage('장소를 검색하고 있습니다.');
      setLocationResults(await searchLocations(locationQuery.trim(), 8));
    } catch (error) {
      showError('장소 검색에 실패했습니다.', error);
    } finally {
      setBusyMessage(null);
    }
  }

  async function requestNearbyPools() {
    try {
      setBusyMessage('현재 위치를 확인하고 있습니다.');
      const granted = await requestLocationPermission();
      if (!granted) {
        Alert.alert('위치 권한이 필요합니다.', '주변 수영장 검색을 위해 위치 권한을 허용해 주세요.');
        return;
      }

      Geolocation.getCurrentPosition(
        async position => {
          try {
            const result = await getNearbyPools(
              position.coords.latitude,
              position.coords.longitude,
              10,
            );
            setNearbyPools(result);
          } catch (error) {
            showError('주변 수영장을 불러오지 못했습니다.', error);
          } finally {
            setBusyMessage(null);
          }
        },
        error => {
          setBusyMessage(null);
          Alert.alert('위치 확인 실패', error.message);
        },
        {enableHighAccuracy: true, timeout: 10000, maximumAge: 60000},
      );
    } catch (error) {
      setBusyMessage(null);
      showError('위치 권한 처리에 실패했습니다.', error);
    }
  }

  async function requestPoolAdd(candidate: PoolLocationCandidate | LocationSearchCandidate) {
    if (!user) {
      Alert.alert('로그인이 필요합니다.', '시설 추가 요청은 로그인 후 사용할 수 있습니다.');
      return;
    }
    try {
      setBusyMessage('시설 추가 요청을 등록하고 있습니다.');
      await createPoolFromLocationCandidate({
        ...candidate,
        alreadyExists: false,
        matchedPoolId: null,
      });
      Alert.alert('요청 완료', '관리자가 검토 후 시설을 추가합니다.');
    } catch (error) {
      showError('시설 추가 요청에 실패했습니다.', error);
    } finally {
      setBusyMessage(null);
    }
  }

  const featuredEvents = events.slice(0, 5);

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.screenContent}>
        <View style={styles.hero}>
          <Text style={styles.eyebrow}>SWIMPULSE MOBILE</Text>
          <Text style={styles.heroTitle}>수영장 접수 알림을 모바일에서도</Text>
          <Text style={styles.heroText}>
            주변 수영장 공지를 확인하고, 모집 기간을 구독해 푸시 알림으로 받아보세요.
          </Text>
          <View style={styles.heroActions}>
            {user ? (
              <Text style={styles.userBadge}>{user.displayName}님</Text>
            ) : (
              <ActionButton label="Google 로그인" onPress={login} />
            )}
            <SecondaryButton label="마이페이지" onPress={() => navigation.navigate('MyPage')} />
            <SecondaryButton label="설정" onPress={() => navigation.navigate('Settings')} />
          </View>
        </View>

        {booting || loading ? <ActivityIndicator color="#047c86" /> : null}
        {busyMessage ? <NoticeBanner text={busyMessage} /> : null}

        <Section title="위치 검색">
          <View style={styles.searchRow}>
            <TextInput
              value={locationQuery}
              onChangeText={setLocationQuery}
              placeholder="수원 수영장, 부천 체육센터"
              placeholderTextColor="#7a8a99"
              style={styles.searchInput}
              returnKeyType="search"
              onSubmitEditing={searchByKeyword}
            />
            <ActionButton label="검색" onPress={searchByKeyword} />
          </View>
          <SecondaryButton label="현재 위치 주변 수영장" onPress={requestNearbyPools} />
          {locationResults.map(candidate => (
            <CandidateCard
              key={`${candidate.title}:${candidate.address}`}
              candidate={candidate}
              onRequestAdd={() => requestPoolAdd(candidate)}
            />
          ))}
        {nearbyPools.map(item => (
            <PoolCard
              key={`nearby:${item.pool.id}`}
              pool={item.pool}
              distanceMeters={item.distanceMeters}
              onScan={() => openNoticeScan(item.pool)}
            />
          ))}
        </Section>

        <Section title="최근 모집 일정">
          {featuredEvents.length === 0 ? (
            <EmptyText text="아직 표시할 모집 일정이 없습니다." />
          ) : (
            featuredEvents.map(event => (
              <View key={event.id} style={styles.eventCard}>
                <View style={styles.rowBetween}>
                  <Text style={styles.eventTitle}>{event.title}</Text>
                  <Text style={styles.statusPill}>{eventStatusLabel(event.status)}</Text>
                </View>
                <Text style={styles.mutedText}>{event.poolName}</Text>
                <Text style={styles.periodText}>
                  {formatShortPeriod(event.registrationStartsAt, event.registrationEndsAt)}
                </Text>
                {subscribedKeys.has(subscriptionKeyFromEvent(event)) ? (
                  <Text style={styles.subscribedText}>구독 중</Text>
                ) : null}
              </View>
            ))
          )}
        </Section>

        <Section title="수영장 목록">
          <Text style={styles.mutedText}>
            전체 {pools.length.toLocaleString('ko-KR')}개 중 {visiblePools.length.toLocaleString('ko-KR')}개 표시
          </Text>
          {visiblePools.map(pool => (
            <PoolCard key={pool.id} pool={pool} onScan={() => openNoticeScan(pool)} />
          ))}
          {pools.length > POOL_PAGE_SIZE ? (
            <MobilePaginationControls
              page={safePoolPage}
              totalPages={poolTotalPages}
              totalItems={pools.length}
              pageSize={POOL_PAGE_SIZE}
              onPageChange={setPoolPage}
            />
          ) : null}
        </Section>
      </ScrollView>

      {scanResult ? (
        <NoticeScanModal
          visible={scanVisible}
          result={scanResult}
          subscriptions={subscriptions}
          onClose={() => setScanVisible(false)}
          onResultChange={setScanResult}
          onSubscriptionsChanged={refreshSubscriptions}
          user={user}
          onLogin={login}
        />
      ) : null}
    </Screen>
  );
}

function NoticeScanModal({
  visible,
  result,
  subscriptions,
  onClose,
  onResultChange,
  onSubscriptionsChanged,
  user,
  onLogin,
}: {
  visible: boolean;
  result: NoticeScanResponse;
  subscriptions: Subscription[];
  onClose: () => void;
  onResultChange: (result: NoticeScanResponse) => void;
  onSubscriptionsChanged: () => Promise<void>;
  user: AppUser | null;
  onLogin: () => Promise<void>;
}) {
  const [pollMessage, setPollMessage] = useState<string | null>(null);
  const [workingKey, setWorkingKey] = useState<string | null>(null);

  const subscribedByKey = useMemo(() => {
    return new Map(subscriptions.map(subscription => [subscriptionKey(subscription), subscription]));
  }, [subscriptions]);

  useEffect(() => {
    if (!visible || !result.notices.some(notice => isOcrInProgress(notice.ocrStatus))) {
      return;
    }

    let stopped = false;
    let elapsed = 0;
    setPollMessage('이미지 공지를 분석하고 있습니다.');

    const timer = setInterval(async () => {
      elapsed += 3000;
      if (elapsed > 30000) {
        setPollMessage('이미지 공지 분석이 지연되고 있습니다. 원문을 확인하거나 잠시 후 다시 시도해 주세요.');
        clearInterval(timer);
        return;
      }

      try {
        const next = await scanPoolNotices(result.poolId);
        if (!stopped) {
          onResultChange(next);
          if (!next.notices.some(notice => isOcrInProgress(notice.ocrStatus))) {
            setPollMessage('이미지 분석 완료');
            clearInterval(timer);
          }
        }
      } catch {
        if (!stopped) {
          setPollMessage('이미지 공지 분석 상태를 다시 확인하지 못했습니다.');
          clearInterval(timer);
        }
      }
    }, 3000);

    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [visible, result, onResultChange]);

  async function subscribe(notice: PoolNotice, period: NormalizedPeriod) {
    if (!user) {
      Alert.alert(
        '로그인이 필요한 작업입니다.',
        '모집 기간 알림을 받으려면 Google 로그인이 필요합니다.',
        [
          {text: '닫기', style: 'cancel'},
          {text: 'Google 로그인', onPress: onLogin},
        ],
      );
      return;
    }

    const baseInput = {
      poolId: result.poolId,
      title: notice.title,
      registrationStartsAt: period.startsAt,
      registrationEndsAt: period.endsAt,
      noticeRegistrationPeriodId: period.id,
      noticeUrl: notice.url,
    };

    const submit = async (input: typeof baseInput) => {
      const key = `${notice.id}:${input.registrationStartsAt}:${input.registrationEndsAt}`;
      try {
        setWorkingKey(key);
        await createSubscription(input);
        await onSubscriptionsChanged();
        Alert.alert('구독 완료', '마이페이지에서 구독을 확인할 수 있습니다.');
      } catch (error) {
        showError('구독 생성에 실패했습니다.', error);
      } finally {
        setWorkingKey(null);
      }
    };

    if (isPastPeriod(period.startsAt, period.endsAt)) {
      const shifted = shiftPeriodToCurrentMonth(period.startsAt, period.endsAt);
      Alert.alert(
        '지난 모집 기간입니다.',
        `예상 알림 기간 ${formatShortPeriod(
          shifted.registrationStartsAt,
          shifted.registrationEndsAt,
        )} 으로 구독할까요?`,
        [
          {text: '취소', style: 'cancel'},
          {
            text: '이 날짜로 구독',
            onPress: () =>
              submit({
                ...baseInput,
                ...shifted,
                noticeRegistrationPeriodId: null,
              }),
          },
        ],
      );
      return;
    }

    await submit(baseInput);
  }

  async function unsubscribe(subscription: Subscription) {
    if (!subscription.event) {
      return;
    }
    try {
      setWorkingKey(`unsubscribe:${subscription.id}`);
      await deleteSubscription(subscription.event.id);
      await onSubscriptionsChanged();
      Alert.alert('구독 해제', '구독을 해제했습니다.');
    } catch (error) {
      showError('구독 해제에 실패했습니다.', error);
    } finally {
      setWorkingKey(null);
    }
  }

  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      <SafeAreaView style={styles.modalRoot}>
        <View style={styles.modalHeader}>
          <View>
            <Text style={styles.eyebrow}>NOTICE SCAN</Text>
            <Text style={styles.modalTitle}>{result.poolName}</Text>
          </View>
          <SecondaryButton label="닫기" onPress={onClose} />
        </View>
        <ScrollView contentContainerStyle={styles.modalContent}>
          <NoticeBanner text={result.message || '공지 확인 결과입니다.'} />
          {pollMessage ? <NoticeBanner text={pollMessage} tone="amber" /> : null}
          {result.notices.length === 0 ? (
            <EmptyText text="기간을 찾은 공지가 없습니다. 원문 공지를 확인해 주세요." />
          ) : (
            result.notices.map(notice => {
              const periods = normalizeNoticePeriods(notice);
              return (
                <View key={notice.id} style={styles.noticeCard}>
                  <Text style={styles.noticeTitle}>{notice.title}</Text>
                  <View style={styles.badgeRow}>
                    <Text style={styles.statusPill}>{notice.extractionStatus}</Text>
                    <Text style={styles.statusPill}>{notice.ocrStatus ?? 'NOT_REQUIRED'}</Text>
                  </View>
                  <SecondaryButton
                    label="원문 보기"
                    onPress={() => Linking.openURL(notice.url)}
                  />
                  {periods.length === 0 ? (
                    <Text style={styles.mutedText}>모집 기간을 찾지 못했습니다.</Text>
                  ) : (
                    periods.map(period => {
                      const syntheticEvent: RegistrationEvent = {
                        id: -notice.id,
                        noticeRegistrationPeriodId: period.id,
                        noticeUrl: notice.url,
                        poolId: result.poolId,
                        poolName: result.poolName,
                        title: notice.title,
                        registrationStartsAt: period.startsAt,
                        registrationEndsAt: period.endsAt,
                          status: 'UPCOMING',
                          sourceValidityStatus: 'ACTIVE',
                          sourceChangedAt: null,
                          sourceChangeReason: null,
                          reminderQueued: false,
                        startQueued: false,
                      };
                      const existing = subscribedByKey.get(subscriptionKeyFromEvent(syntheticEvent));
                      const isWorking =
                        workingKey === `${notice.id}:${period.startsAt}:${period.endsAt}` ||
                        (existing && workingKey === `unsubscribe:${existing.id}`);
                      return (
                        <View key={`${notice.id}:${period.startsAt}:${period.endsAt}`} style={styles.periodRow}>
                          <View style={styles.flexOne}>
                            <Text style={styles.periodLabel}>{period.label}</Text>
                            <Text style={styles.periodText}>
                              {formatShortPeriod(period.startsAt, period.endsAt)}
                            </Text>
                          </View>
                          {isWorking ? (
                            <ActivityIndicator color="#047c86" />
                          ) : existing ? (
                            <SecondaryButton label="해제" onPress={() => unsubscribe(existing)} />
                          ) : (
                            <ActionButton label="구독" onPress={() => subscribe(notice, period)} />
                          )}
                        </View>
                      );
                    })
                  )}
                </View>
              );
            })
          )}
        </ScrollView>
      </SafeAreaView>
    </Modal>
  );
}

function MyPageScreen({
  user,
  setUser,
  route,
}: AppState & {route: {params?: {subscriptionId?: number}}}) {
  const [data, setData] = useState<MyPageData | null>(null);
  const [notifications, setNotifications] = useState<InAppNotification[]>([]);
  const [notificationPage, setNotificationPage] = useState({page: 0, totalPages: 0, last: true});
  const [loading, setLoading] = useState(true);
  const [showClosed, setShowClosed] = useState(false);
  const [editing, setEditing] = useState<Subscription | null>(null);
  const [selectedSubscription, setSelectedSubscription] = useState<Subscription | null>(null);
  const [selectedNotification, setSelectedNotification] = useState<InAppNotification | null>(null);
  const [handledReviewTarget, setHandledReviewTarget] = useState<number | null>(null);
  const [confirmingReview, setConfirmingReview] = useState(false);

  const load = useCallback(async (page = notificationPage.page) => {
    if (!user) {
      setData(null);
      setNotifications([]);
      setLoading(false);
      return;
    }

    setLoading(true);
    try {
      const [myPage, notificationResult] = await Promise.all([
        getMyPage(),
        getNotificationPage(page, 10),
      ]);
      setData(myPage);
      setNotifications(notificationResult.content);
      setNotificationPage({
        page: notificationResult.page,
        totalPages: notificationResult.totalPages,
        last: notificationResult.last,
      });
    } catch (error) {
      showError('마이페이지를 불러오지 못했습니다.', error);
    } finally {
      setLoading(false);
    }
  }, [notificationPage.page, user]);

  useFocusEffect(
    useCallback(() => {
      load(0);
    }, [load]),
  );

  useEffect(() => {
    const targetId = route.params?.subscriptionId;
    if (!targetId || !data || handledReviewTarget === targetId) {
      return;
    }
    const target = data.subscriptions.find(subscription => subscription.id === targetId);
    setHandledReviewTarget(targetId);
    if (target) {
      setSelectedSubscription(target);
    }
  }, [data, handledReviewTarget, route.params?.subscriptionId]);

  async function logout() {
    await signOutFromGoogle();
    setUser(null);
    setData(null);
  }

  async function removeSubscription(subscription: Subscription) {
    if (!subscription.event) {
      return;
    }
    Alert.alert(
      '구독 해제',
      `${subscriptionTitle(subscription)} 구독을 해제할까요?`,
      [
        {text: '취소', style: 'cancel'},
        {
          text: '해제',
          style: 'destructive',
          onPress: async () => {
            try {
              await deleteSubscription(subscription.event!.id);
              await load(0);
            } catch (error) {
              showError('구독 해제에 실패했습니다.', error);
            }
          },
        },
      ],
    );
  }

  async function readNotification(notification: InAppNotification) {
    try {
      await markNotificationRead(notification.id);
      await load(notificationPage.page);
    } catch (error) {
      showError('알림 읽음 처리에 실패했습니다.', error);
    }
  }

  async function closeNotificationDetail() {
    const notification = selectedNotification;
    setSelectedNotification(null);

    if (notification && !notification.readAt) {
      await readNotification(notification);
    }
  }

  function openNotificationDetail(notification: InAppNotification) {
    if (notification.type === 'SOURCE_REVIEW_REQUIRED' && notification.subscriptionId) {
      const subscription = data?.subscriptions.find(candidate => candidate.id === notification.subscriptionId);
      if (subscription) {
        setSelectedSubscription(subscription);
        if (!notification.readAt) {
          readNotification(notification).catch(() => undefined);
        }
        return;
      }
    }
    setSelectedNotification(notification);
  }

  async function confirmCurrentPeriod(subscription: Subscription) {
    try {
      setConfirmingReview(true);
      const updated = await confirmSubscriptionSourceReview(subscription.id);
      setSelectedSubscription(updated);
      await load(0);
      Alert.alert('구독 검토 완료', '현재 기간 기준으로 알림이 다시 동작합니다.');
    } catch (error) {
      showError('구독 검토를 완료하지 못했습니다.', error);
    } finally {
      setConfirmingReview(false);
    }
  }

  if (!user) {
    return (
      <Screen>
        <View style={styles.centerBox}>
          <Text style={styles.sectionTitle}>로그인이 필요합니다.</Text>
          <ActionButton
            label="Google 로그인"
            onPress={async () => {
              try {
                setUser(await signInWithGoogle());
              } catch (error) {
                showError('Google 로그인에 실패했습니다.', error);
              }
            }}
          />
        </View>
      </Screen>
    );
  }

  const reviewRequiredSubscriptions = data?.subscriptions.filter(subscription => subscription.reviewStatus === 'REVIEW_REQUIRED') ?? [];
  const activeSubscriptions = [...(data?.subscriptions ?? [])]
    .filter(subscription => !isEventClosed(subscription.event))
    .sort((left, right) => Number(right.reviewStatus === 'REVIEW_REQUIRED') - Number(left.reviewStatus === 'REVIEW_REQUIRED'));
  const closedSubscriptions = data?.subscriptions.filter(subscription => isEventClosed(subscription.event)) ?? [];

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.screenContent}>
        <View style={styles.profileCard}>
          <View>
            <Text style={styles.eyebrow}>MY PAGE</Text>
            <Text style={styles.heroTitle}>{user.displayName}</Text>
            <Text style={styles.mutedText}>{user.email}</Text>
          </View>
          <SecondaryButton label="로그아웃" onPress={logout} />
        </View>

        {loading ? <ActivityIndicator color="#047c86" /> : null}

        {data ? (
          <View style={styles.metricGrid}>
            <Metric label="구독" value={data.metrics.subscriptionCount} />
            <Metric label="안읽음" value={data.metrics.unreadNotificationCount} />
            <Metric label="기기" value={data.metrics.activeDeviceCount} />
          </View>
        ) : null}

        {reviewRequiredSubscriptions.length > 0 ? (
          <NoticeBanner
            tone="amber"
            text={`홈페이지 출처 변경으로 구독 검토가 필요합니다. 잘못 연결된 홈페이지 출처를 올바른 시설 홈페이지로 교정했습니다. 검토 대상 ${reviewRequiredSubscriptions.length}건이 진행 중인 구독 상단에 표시됩니다.`}
          />
        ) : null}

        <Section title="진행 중인 구독">
          {activeSubscriptions.length === 0 ? (
            <EmptyText text="진행 중인 구독이 없습니다." />
          ) : (
            activeSubscriptions.map(subscription => (
              <SubscriptionCard
                key={subscription.id}
                subscription={subscription}
                editable
                onOpen={() => setSelectedSubscription(subscription)}
                onEdit={() => setEditing(subscription)}
                onDelete={() => removeSubscription(subscription)}
              />
            ))
          )}
        </Section>

        <Section title={`마감된 구독 ${closedSubscriptions.length}건`}>
          <SecondaryButton
            label={showClosed ? '접기' : '펼치기'}
            onPress={() => setShowClosed(value => !value)}
          />
          {showClosed
            ? closedSubscriptions.map(subscription => (
                <SubscriptionCard
                  key={subscription.id}
                  subscription={subscription}
                  editable={false}
                  onOpen={() => setSelectedSubscription(subscription)}
                  onDelete={() => removeSubscription(subscription)}
                />
              ))
            : null}
        </Section>

        <Section title="최근 알림">
          {notifications.length === 0 ? (
            <EmptyText text="알림이 없습니다." />
          ) : (
            notifications.map(notification => (
              <Pressable
                key={notification.id}
                accessibilityRole="button"
                onPress={() => openNotificationDetail(notification)}
                style={styles.notificationCard}>
                <View style={styles.rowBetween}>
                  <Text style={styles.noticeTitle}>{notification.title}</Text>
                  <Text style={notification.readAt ? styles.readBadge : styles.unreadBadge}>
                    {notification.readAt ? '읽음' : '안읽음'}
                  </Text>
                </View>
                <Text style={styles.mutedText}>{notification.message}</Text>
                <Text style={styles.periodText}>{formatDateTime(notification.createdAt)}</Text>
                <View style={styles.buttonRow}>
                  {!notification.readAt ? (
                    <ActionButton label="읽음" onPress={() => readNotification(notification)} />
                  ) : null}
                  {notification.noticeUrl ? (
                    <SecondaryButton
                      label="원문 보기"
                      onPress={() => Linking.openURL(notification.noticeUrl!)}
                    />
                  ) : null}
                </View>
              </Pressable>
            ))
          )}
          <View style={styles.rowBetween}>
            <SecondaryButton
              label="이전"
              disabled={notificationPage.page <= 0}
              onPress={() => load(notificationPage.page - 1)}
            />
            <Text style={styles.mutedText}>
              {notificationPage.totalPages === 0
                ? '0 / 0'
                : `${notificationPage.page + 1} / ${notificationPage.totalPages}`}
            </Text>
            <SecondaryButton
              label="다음"
              disabled={notificationPage.last}
              onPress={() => load(notificationPage.page + 1)}
            />
          </View>
        </Section>
      </ScrollView>
      {editing ? (
        <EditSubscriptionModal
          subscription={editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await load(0);
          }}
        />
      ) : null}
      <SubscriptionDetailModal
        subscription={selectedSubscription}
        onClose={() => setSelectedSubscription(null)}
        onEdit={subscription => {
          setSelectedSubscription(null);
          setEditing(subscription);
        }}
        onDelete={subscription => {
          setSelectedSubscription(null);
          removeSubscription(subscription);
        }}
        confirmingReview={confirmingReview}
        onConfirmCurrent={confirmCurrentPeriod}
      />
      <PushNotificationModal
        notification={selectedNotification}
        fallback={null}
        loading={false}
        onClose={closeNotificationDetail}
        onReview={subscriptionId => {
          const subscription = data?.subscriptions.find(candidate => candidate.id === subscriptionId);
          setSelectedNotification(null);
          if (subscription) {
            setSelectedSubscription(subscription);
          }
        }}
      />
    </Screen>
  );
}

function SettingsScreen({user, setUser}: AppState) {
  const [status, setStatus] = useState('확인 전');
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function checkApi() {
    try {
      setLoading(true);
      const result = await health();
      setStatus(result.status);
    } catch (error) {
      setStatus(`실패: ${String(error)}`);
    } finally {
      setLoading(false);
    }
  }

  async function login() {
    try {
      setLoading(true);
      setUser(await signInWithGoogle());
    } catch (error) {
      showError('Google 로그인에 실패했습니다.', error);
    } finally {
      setLoading(false);
    }
  }

  async function registerPush() {
    setLoading(true);
    const result = await registerPushToken();
    setMessage(result.message);
    setLoading(false);
  }

  async function unregisterPush() {
    setLoading(true);
    const result = await unregisterPushToken();
    setMessage(result.message);
    setLoading(false);
  }

  async function testNotification() {
    try {
      setLoading(true);
      const notification = await sendMobileTestNotification();
      setMessage(`테스트 알림을 queue에 넣었습니다. notificationId=${notification.id}`);
    } catch (error) {
      showError('테스트 알림 요청에 실패했습니다.', error);
    } finally {
      setLoading(false);
    }
  }

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.screenContent}>
        <Section title="API 연결">
          <Text style={styles.mutedText}>API: {API_BASE_URL}</Text>
          <Text style={styles.periodText}>상태: {status}</Text>
          <ActionButton label="API 상태 확인" onPress={checkApi} />
        </Section>

        <Section title="계정">
          {user ? (
            <>
              <Text style={styles.noticeTitle}>{user.displayName}</Text>
              <Text style={styles.mutedText}>{user.email}</Text>
              <SecondaryButton
                label="로그아웃"
                onPress={async () => {
                  await signOutFromGoogle();
                  setUser(null);
                }}
              />
            </>
          ) : (
            <ActionButton label="Google 로그인" onPress={login} />
          )}
        </Section>

        <Section title="푸시 알림">
          <Text style={styles.mutedText}>
            실제 FCM 토큰 등록은 Firebase Android 설정 파일이 있을 때 동작합니다.
          </Text>
          <View style={styles.buttonRow}>
            <ActionButton label="기기 등록" onPress={registerPush} disabled={!user} />
            <SecondaryButton label="등록 해제" onPress={unregisterPush} disabled={!user} />
          </View>
          <ActionButton label="테스트 알림" onPress={testNotification} disabled={!user} />
          {message ? <NoticeBanner text={message} /> : null}
        </Section>

        {loading ? <ActivityIndicator color="#047c86" /> : null}
      </ScrollView>
    </Screen>
  );
}

function EditSubscriptionModal({
  subscription,
  onClose,
  onSaved,
}: {
  subscription: Subscription;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const event = subscription.event;
  const [title, setTitle] = useState(event?.title ?? subscription.pool.name);
  const [startParts, setStartParts] = useState<DateTimeParts>(() => toDateTimeParts(event?.registrationStartsAt));
  const [endParts, setEndParts] = useState<DateTimeParts>(() => toDateTimeParts(event?.registrationEndsAt));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    if (!event) {
      return;
    }

    const nextStart = fromDateTimeParts(startParts);
    const nextEnd = fromDateTimeParts(endParts);
    if (!nextStart || !nextEnd || new Date(nextStart).getTime() >= new Date(nextEnd).getTime()) {
      setError('시작/종료 날짜와 시간을 다시 확인해 주세요.');
      return;
    }

    try {
      setSaving(true);
      await updateSubscriptionPeriod(subscription.id, {
        title,
        registrationStartsAt: nextStart,
        registrationEndsAt: nextEnd,
      });
      await onSaved();
    } catch (saveError) {
      showError('구독 기간 수정에 실패했습니다.', saveError);
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal visible animationType="fade" transparent onRequestClose={onClose}>
      <View style={styles.dimmed}>
        <View style={styles.dialog}>
          <View style={styles.rowBetween}>
            <View>
              <Text style={styles.eyebrow}>EDIT SUBSCRIPTION</Text>
              <Text style={styles.modalTitle}>구독 기간 수정</Text>
            </View>
            <SecondaryButton label="닫기" onPress={onClose} />
          </View>
          <Text style={styles.mutedText}>
            {subscription.pool.name} 구독만 수정됩니다. 다른 사용자의 구독 기간은 바뀌지 않습니다.
          </Text>

          <ScrollView contentContainerStyle={styles.dialogForm} keyboardShouldPersistTaps="handled">
            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>구독명</Text>
              <TextInput
                value={title}
                onChangeText={setTitle}
                editable={!saving}
                style={styles.input}
                placeholder="구독명을 입력하세요"
                placeholderTextColor="#7a8a99"
                maxLength={120}
              />
            </View>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>시작 시각</Text>
              <DateTimeEditor
                value={startParts}
                onChange={setStartParts}
                disabled={saving}
                hasError={Boolean(error)}
                onClearError={() => setError(null)}
              />
              <QuickTimeRow
                disabled={saving}
                onPick={(hour, minute) => {
                  setStartParts(current => ({...current, hour, minute}));
                  setError(null);
                }}
                options={[
                  ['09', '00', '오전 9시'],
                  ['10', '00', '오전 10시'],
                  ['12', '00', '정오'],
                ]}
              />
              <Text style={styles.fieldHint}>날짜와 시간을 나눠 입력하세요. 한국 시간 기준입니다.</Text>
            </View>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>종료 시각</Text>
              <DateTimeEditor
                value={endParts}
                onChange={setEndParts}
                disabled={saving}
                hasError={Boolean(error)}
                onClearError={() => setError(null)}
              />
              <QuickTimeRow
                disabled={saving}
                onPick={(hour, minute) => {
                  setEndParts(current => ({...current, hour, minute}));
                  setError(null);
                }}
                options={[
                  ['18', '00', '오후 6시'],
                  ['23', '00', '오후 11시'],
                  ['23', '59', '마감 23:59'],
                ]}
              />
              <Text style={styles.fieldHint}>종료 시각은 시작 시각보다 뒤여야 합니다.</Text>
            </View>

            {error ? (
              <View style={styles.validationBox}>
                <Text style={styles.errorText}>{error}</Text>
                <Text style={styles.validationText}>날짜 형식과 시작/종료 순서를 확인하세요.</Text>
              </View>
            ) : null}

            <Text style={styles.infoBox}>
              공지 파싱 결과가 잘못됐을 때 직접 기간을 보정할 수 있습니다. 저장 후에는 수정한 기간 기준으로
              리마인더와 시작 알림이 계산됩니다.
            </Text>
          </ScrollView>

          <View style={styles.buttonRowEnd}>
            <SecondaryButton label="취소" onPress={onClose} disabled={saving} />
            {saving ? <ActivityIndicator color="#047c86" /> : <ActionButton label="기간 저장" onPress={save} />}
          </View>
        </View>
      </View>
    </Modal>
  );
}

function DateTimeEditor({
  value,
  onChange,
  disabled,
  hasError,
  onClearError,
}: {
  value: DateTimeParts;
  onChange: (value: DateTimeParts) => void;
  disabled: boolean;
  hasError: boolean;
  onClearError: () => void;
}) {
  function update(partial: Partial<DateTimeParts>) {
    onChange({...value, ...partial});
    onClearError();
  }

  return (
    <View style={styles.dateTimeEditor}>
      <TextInput
        value={value.date}
        onChangeText={date => update({date})}
        editable={!disabled}
        style={[styles.input, styles.dateInput, hasError ? styles.inputError : null]}
        placeholder="2026-07-15"
        placeholderTextColor="#7a8a99"
        keyboardType="numbers-and-punctuation"
        maxLength={10}
      />
      <TextInput
        value={value.hour}
        onChangeText={hour => update({hour: onlyDigits(hour).slice(0, 2)})}
        editable={!disabled}
        style={[styles.input, styles.timeInput, hasError ? styles.inputError : null]}
        placeholder="09"
        placeholderTextColor="#7a8a99"
        keyboardType="number-pad"
        maxLength={2}
      />
      <Text style={styles.timeSeparator}>:</Text>
      <TextInput
        value={value.minute}
        onChangeText={minute => update({minute: onlyDigits(minute).slice(0, 2)})}
        editable={!disabled}
        style={[styles.input, styles.timeInput, hasError ? styles.inputError : null]}
        placeholder="00"
        placeholderTextColor="#7a8a99"
        keyboardType="number-pad"
        maxLength={2}
      />
    </View>
  );
}

function QuickTimeRow({
  options,
  disabled,
  onPick,
}: {
  options: Array<[string, string, string]>;
  disabled: boolean;
  onPick: (hour: string, minute: string) => void;
}) {
  return (
    <View style={styles.quickTimeRow}>
      {options.map(([hour, minute, label]) => (
        <SecondaryButton
          key={`${hour}:${minute}`}
          label={label}
          onPress={() => onPick(hour, minute)}
          disabled={disabled}
        />
      ))}
    </View>
  );
}

function toDateTimeParts(value: string | null | undefined): DateTimeParts {
  const input = value ? toInputDateTime(value) : '';
  const [date = '', time = ''] = input.split(' ');
  const [hour = '', minute = ''] = time.split(':');
  return {date, hour, minute};
}

function fromDateTimeParts(value: DateTimeParts) {
  const date = normalizeDateInput(value.date);
  const hour = onlyDigits(value.hour).padStart(2, '0');
  const minute = onlyDigits(value.minute).padStart(2, '0');
  if (!date || hour.length !== 2 || minute.length !== 2) {
    return null;
  }
  return fromInputDateTime(`${date} ${hour}:${minute}`);
}

function normalizeDateInput(value: string) {
  const normalized = value.trim().replace(/\./g, '-').replace(/\//g, '-');
  const match = normalized.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
  if (!match) {
    return null;
  }
  const [, year, month, day] = match;
  return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
}

function onlyDigits(value: string) {
  return value.replace(/\D/g, '');
}

function PushNotificationModal({
  notification,
  fallback,
  loading,
  onClose,
  onReview,
}: {
  notification: InAppNotification | null;
  fallback: ReceivedPushMessage | null;
  loading: boolean;
  onClose: () => void;
  onReview?: (subscriptionId: number) => void;
}) {
  const visible = loading || Boolean(notification || fallback);
  const title = notification?.title ?? fallback?.title ?? 'SwimPulse 알림';
  const message = notification?.message ?? fallback?.body ?? '새 알림이 도착했습니다.';
  const noticeUrl = notification?.noticeUrl ?? fallback?.noticeUrl;
  const currentHomepageUrl = notification?.currentHomepageUrl ?? fallback?.currentHomepageUrl;
  const subscriptionId = notification?.subscriptionId ?? Number(fallback?.subscriptionId);
  const sourceReview = notification?.type === 'SOURCE_REVIEW_REQUIRED' || fallback?.type === 'SOURCE_REVIEW_REQUIRED';
  const poolName = notification?.poolName;
  const eventTitle = notification?.eventTitle;
  const arrivedAt = notification?.createdAt ? formatDateTime(notification.createdAt) : null;
  const isUnread = notification ? !notification.readAt : true;

  return (
    <Modal visible={visible} animationType="fade" transparent onRequestClose={onClose}>
      <View style={styles.dimmed}>
        <View style={styles.dialog}>
          {loading && !notification ? (
            <View style={styles.centeredDialogContent}>
              <ActivityIndicator color="#047c86" />
              <Text style={styles.mutedText}>알림을 불러오는 중입니다.</Text>
            </View>
          ) : (
            <>
              <View style={styles.rowBetween}>
                <Text style={styles.statusPill}>PUSH 알림</Text>
                <Text style={isUnread ? styles.unreadBadge : styles.readBadge}>
                  {isUnread ? '안읽음' : '읽음'}
                </Text>
              </View>
              <Text style={styles.modalTitle}>{title}</Text>
              <Text style={styles.heroText}>{message}</Text>

              {poolName || eventTitle || arrivedAt ? (
                <View style={styles.notificationPreview}>
                  {poolName ? <Text style={styles.noticeTitle}>{poolName}</Text> : null}
                  {eventTitle ? <Text style={styles.mutedText}>{eventTitle}</Text> : null}
                  {arrivedAt ? <Text style={styles.periodText}>도착 {arrivedAt}</Text> : null}
                </View>
              ) : null}

              <View style={styles.buttonRow}>
                {noticeUrl ? (
                  <SecondaryButton
                    label={sourceReview ? '기존 공지 보기' : '원문 보기'}
                    onPress={() => Linking.openURL(noticeUrl)}
                  />
                ) : null}
                {sourceReview && currentHomepageUrl ? (
                  <SecondaryButton label="새 홈페이지 확인" onPress={() => Linking.openURL(currentHomepageUrl)} />
                ) : null}
                {sourceReview && Number.isInteger(subscriptionId) && subscriptionId > 0 && onReview ? (
                  <ActionButton label="구독 검토하기" onPress={() => onReview(subscriptionId)} />
                ) : null}
                <ActionButton label="확인" onPress={onClose} />
              </View>
              <Text style={styles.helperText}>확인하면 읽음 처리됩니다.</Text>
            </>
          )}
        </View>
      </View>
    </Modal>
  );
}

function SubscriptionDetailModal({
  subscription,
  onClose,
  onEdit,
  onDelete,
  confirmingReview,
  onConfirmCurrent,
}: {
  subscription: Subscription | null;
  onClose: () => void;
  onEdit: (subscription: Subscription) => void;
  onDelete: (subscription: Subscription) => void;
  confirmingReview: boolean;
  onConfirmCurrent: (subscription: Subscription) => void;
}) {
  if (!subscription) {
    return null;
  }

  const event = subscription.event;
  const poolName = event?.poolName ?? subscription.pool.name;
  const canEdit = Boolean(event && !isEventClosed(event));

  return (
    <Modal visible animationType="fade" transparent onRequestClose={onClose}>
      <View style={styles.dimmed}>
        <View style={styles.dialog}>
          <View style={styles.rowBetween}>
            <View>
              <Text style={styles.eyebrow}>SUBSCRIPTION</Text>
              <Text style={styles.modalTitle}>구독 상세</Text>
            </View>
            <SecondaryButton label="닫기" onPress={onClose} />
          </View>

          <View style={styles.notificationPreview}>
            <View style={styles.badgeRow}>
              {event ? <Text style={styles.statusPill}>{eventStatusLabel(event.status)}</Text> : null}
              <Text style={styles.statusPill}>{poolName}</Text>
            </View>
            <Text style={styles.noticeTitle}>{event?.title ?? '기간 정보가 없는 구독'}</Text>
            {event ? (
              <Text style={styles.periodText}>
                {formatShortPeriod(event.registrationStartsAt, event.registrationEndsAt)}
              </Text>
            ) : null}
            <Text style={styles.mutedText}>구독 생성 {formatDateTime(subscription.createdAt)}</Text>
          </View>

          {subscription.reviewStatus === 'REVIEW_REQUIRED' ? (
            <NoticeBanner
              text={subscription.reviewReason ?? '홈페이지 출처가 변경되었습니다. 기존 공지와 새 홈페이지를 비교해 구독 기간을 검토해주세요.'}
              tone="amber"
            />
          ) : null}

          <View style={styles.buttonRow}>
            {event?.noticeUrl ? (
              <SecondaryButton label="기존 공지 보기" onPress={() => Linking.openURL(event.noticeUrl!)} />
            ) : null}
            {subscription.pool.homepageUrl ? (
              <SecondaryButton label="새 홈페이지 확인" onPress={() => Linking.openURL(subscription.pool.homepageUrl!)} />
            ) : null}
            {subscription.reviewStatus === 'REVIEW_REQUIRED' ? (
              <ActionButton
                label={confirmingReview ? '처리 중...' : '현재 기간 유지'}
                onPress={() => onConfirmCurrent(subscription)}
                disabled={confirmingReview}
              />
            ) : null}
            {canEdit ? <SecondaryButton label="기간 수정" onPress={() => onEdit(subscription)} /> : null}
            <ActionButton label="구독 해제" onPress={() => onDelete(subscription)} disabled={!event} />
          </View>

          {!canEdit ? (
            <Text style={styles.infoBox}>마감된 구독은 기간 수정이 불가능하며 구독 해제만 가능합니다.</Text>
          ) : null}
        </View>
      </View>
    </Modal>
  );
}

type NormalizedPeriod = {
  id: number | null;
  label: string;
  startsAt: string;
  endsAt: string;
};

function normalizeNoticePeriods(notice: PoolNotice): NormalizedPeriod[] {
  const periods = notice.registrationPeriods ?? [];
  if (periods.length > 0) {
    return periods.map((period: NoticeRegistrationPeriod, index) => ({
      id: period.id,
      label: normalizePeriodLabel(period.label, index),
      startsAt: period.startsAt,
      endsAt: period.endsAt,
    }));
  }

  if (notice.registrationStartsAt && notice.registrationEndsAt) {
    return [
      {
        id: null,
        label: '모집 기간',
        startsAt: notice.registrationStartsAt,
        endsAt: notice.registrationEndsAt,
      },
    ];
  }

  return [];
}

function normalizePeriodLabel(label: string | null, index: number) {
  if (!label || /^기간\s*\d+$/i.test(label.trim())) {
    return index === 0 ? '모집 기간' : `모집 기간 ${index + 1}`;
  }
  return label;
}

function PoolCard({
  pool,
  distanceMeters,
  onScan,
}: {
  pool: Pool;
  distanceMeters?: number;
  onScan: () => void;
}) {
  const canScanNotices = Boolean(pool.homepageUrl);
  return (
    <View style={styles.poolCard}>
      <View style={styles.rowBetween}>
        <Text style={styles.poolName}>{pool.name}</Text>
        {pool.district ? <Text style={styles.statusPill}>{pool.district}</Text> : null}
      </View>
      <Text style={styles.mutedText}>{pool.roadNameAddress || pool.lotNumberAddress || '주소 정보 없음'}</Text>
      <Text style={styles.periodText}>
        {[pool.indoorOutdoorTypeName, pool.standardPoolLengthMeters ? `${pool.standardPoolLengthMeters}m` : null, pool.standardPoolLaneCount ? `${pool.standardPoolLaneCount}레인` : null]
          .filter(Boolean)
          .join('  ') || '시설 정보 확인 중'}
      </Text>
      {distanceMeters !== undefined ? (
        <Text style={styles.mutedText}>현재 위치에서 약 {(distanceMeters / 1000).toFixed(1)}km</Text>
      ) : null}
      {!canScanNotices ? (
        <NoticeBanner
          text="홈페이지를 찾을 수 없어 공지 확인을 할 수 없습니다. 시설 정보가 보강되면 이용할 수 있어요."
          tone="amber"
        />
      ) : null}
      <View style={styles.buttonRow}>
        {pool.homepageUrl ? (
          <SecondaryButton label="홈페이지" onPress={() => Linking.openURL(pool.homepageUrl!)} />
        ) : null}
        <ActionButton label="공지 확인" onPress={onScan} disabled={!canScanNotices} />
      </View>
    </View>
  );
}

function CandidateCard({
  candidate,
  onRequestAdd,
}: {
  candidate: LocationSearchCandidate;
  onRequestAdd: () => void;
}) {
  return (
    <View style={styles.poolCard}>
      <Text style={styles.poolName}>{candidate.title}</Text>
      <Text style={styles.mutedText}>{candidate.roadAddress || candidate.address || '주소 정보 없음'}</Text>
      <Text style={styles.periodText}>{candidate.category || '시설'}</Text>
      <View style={styles.buttonRow}>
        {candidate.link ? <SecondaryButton label="원문 보기" onPress={() => Linking.openURL(candidate.link!)} /> : null}
        <ActionButton label="이 시설 추가" onPress={onRequestAdd} />
      </View>
    </View>
  );
}

function SubscriptionCard({
  subscription,
  editable,
  onOpen,
  onEdit,
  onDelete,
}: {
  subscription: Subscription;
  editable: boolean;
  onOpen: () => void;
  onEdit?: () => void;
  onDelete: () => void;
}) {
  const event = subscription.event;
  const needsReview = subscription.reviewStatus === 'REVIEW_REQUIRED';
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onOpen}
      style={[styles.poolCard, needsReview ? styles.reviewSubscriptionCard : null]}>
      <View style={styles.rowBetween}>
        <Text style={styles.poolName}>{subscriptionTitle(subscription)}</Text>
        <View style={styles.badgeRow}>
          {needsReview ? <Text style={styles.reviewStatusPill}>검토 필요</Text> : null}
          <Text style={styles.statusPill}>{event ? eventStatusLabel(event.status) : '수동'}</Text>
        </View>
      </View>
      <Text style={styles.mutedText}>{subscription.pool.name}</Text>
      {event ? (
        <Text style={styles.periodText}>
          {formatShortPeriod(event.registrationStartsAt, event.registrationEndsAt)}
        </Text>
      ) : null}
      {needsReview ? (
        <View style={styles.reviewSubscriptionNotice}>
          <Text style={styles.reviewSubscriptionTitle}>홈페이지 출처 변경으로 구독 검토가 필요합니다.</Text>
          <Text style={styles.reviewSubscriptionText}>
            {subscription.reviewReason ?? '잘못 연결된 홈페이지 출처를 올바른 시설 홈페이지로 교정했습니다. 기존 공지와 새 홈페이지를 확인해주세요.'}
          </Text>
          <SecondaryButton label="검토하기" onPress={onOpen} />
        </View>
      ) : null}
      <View style={styles.buttonRow}>
        {event?.noticeUrl ? (
          <SecondaryButton label="원문 보기" onPress={() => Linking.openURL(event.noticeUrl!)} />
        ) : null}
        {editable && onEdit ? <SecondaryButton label="기간 수정" onPress={onEdit} /> : null}
        <ActionButton label="구독 해제" onPress={onDelete} />
      </View>
    </Pressable>
  );
}

function Metric({label, value}: {label: string; value: number}) {
  return (
    <View style={styles.metricCard}>
      <Text style={styles.metricValue}>{value}</Text>
      <Text style={styles.mutedText}>{label}</Text>
    </View>
  );
}

function MobilePaginationControls({
  page,
  totalPages,
  totalItems,
  pageSize,
  onPageChange,
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  pageSize: number;
  onPageChange: (page: number) => void;
}) {
  const start = totalItems === 0 ? 0 : (page - 1) * pageSize + 1;
  const end = Math.min(totalItems, page * pageSize);

  return (
    <View style={styles.paginationBox}>
      <Text style={styles.paginationText}>
        {start.toLocaleString('ko-KR')}-{end.toLocaleString('ko-KR')} / {totalItems.toLocaleString('ko-KR')}
      </Text>
      <View style={styles.paginationButtons}>
        <SecondaryButton
          label="이전"
          onPress={() => onPageChange(Math.max(1, page - 1))}
          disabled={page <= 1}
        />
        <Text style={styles.paginationPage}>
          {page} / {totalPages}
        </Text>
        <SecondaryButton
          label="다음"
          onPress={() => onPageChange(Math.min(totalPages, page + 1))}
          disabled={page >= totalPages}
        />
      </View>
    </View>
  );
}

function Section({title, children}: {title: string; children: React.ReactNode}) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

function Screen({children}: {children: React.ReactNode}) {
  return <SafeAreaView style={styles.root}>{children}</SafeAreaView>;
}

function ActionButton({
  label,
  onPress,
  disabled,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <TouchableOpacity
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={[styles.actionButton, disabled ? styles.disabledButton : null]}>
      <Text style={styles.actionButtonText}>{label}</Text>
    </TouchableOpacity>
  );
}

function SecondaryButton({
  label,
  onPress,
  disabled,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={[styles.secondaryButton, disabled ? styles.disabledButton : null]}>
      <Text style={styles.secondaryButtonText}>{label}</Text>
    </Pressable>
  );
}

function NoticeBanner({text, tone = 'blue'}: {text: string; tone?: 'blue' | 'amber'}) {
  return (
    <View style={[styles.noticeBanner, tone === 'amber' ? styles.amberBanner : null]}>
      <Text style={styles.noticeBannerText}>{text}</Text>
    </View>
  );
}

function EmptyText({text}: {text: string}) {
  return <Text style={styles.emptyText}>{text}</Text>;
}

async function requestLocationPermission() {
  if (Platform.OS !== 'android') {
    return true;
  }
  const result = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
  );
  return result === PermissionsAndroid.RESULTS.GRANTED;
}

function showError(title: string, error: unknown) {
  if (error instanceof ApiError) {
    Alert.alert(title, `${error.status}: ${error.message}`);
    return;
  }
  Alert.alert(title, String(error));
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#eef7fb',
  },
  screenContent: {
    gap: 18,
    padding: 18,
    paddingBottom: 36,
  },
  hero: {
    gap: 12,
    borderRadius: 20,
    padding: 20,
    backgroundColor: '#dff3f5',
    borderWidth: 1,
    borderColor: '#c4e4e8',
  },
  eyebrow: {
    color: '#047c86',
    fontSize: 12,
    fontWeight: '800',
  },
  heroTitle: {
    color: '#112d42',
    fontSize: 24,
    fontWeight: '900',
  },
  heroText: {
    color: '#3f596c',
    fontSize: 15,
    lineHeight: 22,
  },
  heroActions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    alignItems: 'center',
  },
  userBadge: {
    color: '#063f46',
    fontWeight: '800',
    borderRadius: 16,
    backgroundColor: '#ffffff',
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  section: {
    gap: 12,
  },
  sectionTitle: {
    color: '#112d42',
    fontSize: 18,
    fontWeight: '900',
  },
  searchRow: {
    flexDirection: 'row',
    gap: 8,
    alignItems: 'center',
  },
  searchInput: {
    flex: 1,
    minHeight: 46,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#b7ced9',
    backgroundColor: '#ffffff',
    color: '#112d42',
    paddingHorizontal: 14,
  },
  input: {
    minHeight: 48,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#b7ced9',
    backgroundColor: '#ffffff',
    color: '#112d42',
    paddingHorizontal: 14,
  },
  dateTimeEditor: {
    flexDirection: 'row',
    gap: 8,
    alignItems: 'center',
  },
  dateInput: {
    flex: 1,
  },
  timeInput: {
    width: 58,
    textAlign: 'center',
    paddingHorizontal: 8,
  },
  timeSeparator: {
    color: '#244a5f',
    fontSize: 18,
    fontWeight: '900',
  },
  inputError: {
    borderColor: '#dc2626',
    backgroundColor: '#fff7f7',
  },
  errorText: {
    color: '#dc2626',
    fontWeight: '800',
  },
  poolCard: {
    gap: 8,
    borderRadius: 18,
    backgroundColor: '#ffffff',
    padding: 16,
    borderWidth: 1,
    borderColor: '#d6e5eb',
  },
  reviewSubscriptionCard: {
    borderColor: '#fdba74',
    backgroundColor: '#fffaf3',
  },
  reviewSubscriptionNotice: {
    gap: 6,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#fed7aa',
    backgroundColor: '#fff7ed',
    padding: 12,
  },
  reviewSubscriptionTitle: {
    color: '#9a3412',
    fontSize: 14,
    fontWeight: '900',
  },
  reviewSubscriptionText: {
    color: '#9a3412',
    fontSize: 13,
    lineHeight: 19,
  },
  eventCard: {
    gap: 8,
    borderRadius: 16,
    backgroundColor: '#ffffff',
    padding: 14,
    borderWidth: 1,
    borderColor: '#d6e5eb',
  },
  notificationCard: {
    gap: 8,
    borderRadius: 16,
    backgroundColor: '#ffffff',
    padding: 14,
    borderWidth: 1,
    borderColor: '#d6e5eb',
  },
  notificationPreview: {
    gap: 6,
    borderRadius: 16,
    backgroundColor: '#ffffff',
    padding: 14,
    borderWidth: 1,
    borderColor: '#d6e5eb',
  },
  noticeCard: {
    gap: 10,
    borderRadius: 18,
    backgroundColor: '#ffffff',
    padding: 16,
    borderWidth: 1,
    borderColor: '#d6e5eb',
  },
  poolName: {
    flex: 1,
    color: '#112d42',
    fontSize: 17,
    fontWeight: '900',
  },
  eventTitle: {
    flex: 1,
    color: '#112d42',
    fontSize: 16,
    fontWeight: '800',
  },
  noticeTitle: {
    flex: 1,
    color: '#112d42',
    fontSize: 16,
    fontWeight: '900',
  },
  mutedText: {
    color: '#5c7080',
    fontSize: 14,
    lineHeight: 20,
  },
  periodText: {
    color: '#244a5f',
    fontSize: 14,
    fontWeight: '700',
  },
  subscribedText: {
    color: '#047c86',
    fontSize: 14,
    fontWeight: '900',
  },
  statusPill: {
    overflow: 'hidden',
    borderRadius: 12,
    backgroundColor: '#e5f5f4',
    color: '#047c86',
    paddingHorizontal: 8,
    paddingVertical: 4,
    fontSize: 12,
    fontWeight: '900',
  },
  reviewStatusPill: {
    overflow: 'hidden',
    borderRadius: 12,
    backgroundColor: '#ffedd5',
    color: '#9a3412',
    paddingHorizontal: 8,
    paddingVertical: 4,
    fontSize: 12,
    fontWeight: '900',
  },
  readBadge: {
    color: '#6b7280',
    fontSize: 12,
    fontWeight: '800',
  },
  unreadBadge: {
    color: '#047c86',
    fontSize: 12,
    fontWeight: '900',
  },
  rowBetween: {
    flexDirection: 'row',
    gap: 10,
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  buttonRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    alignItems: 'center',
  },
  buttonRowEnd: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    alignItems: 'center',
    justifyContent: 'flex-end',
  },
  paginationBox: {
    gap: 10,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#d6e5eb',
    backgroundColor: '#f6fbfb',
    padding: 12,
  },
  paginationText: {
    color: '#5c7080',
    fontSize: 13,
    fontWeight: '800',
  },
  paginationButtons: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  paginationPage: {
    minWidth: 54,
    color: '#112d42',
    fontSize: 14,
    fontWeight: '900',
    textAlign: 'center',
  },
  quickTimeRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
  },
  badgeRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
  },
  actionButton: {
    minHeight: 42,
    justifyContent: 'center',
    alignItems: 'center',
    borderRadius: 14,
    backgroundColor: '#047c86',
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  actionButtonText: {
    color: '#ffffff',
    fontWeight: '900',
  },
  secondaryButton: {
    minHeight: 42,
    justifyContent: 'center',
    alignItems: 'center',
    borderRadius: 14,
    backgroundColor: '#ffffff',
    borderWidth: 1,
    borderColor: '#b7ced9',
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  secondaryButtonText: {
    color: '#244a5f',
    fontWeight: '900',
  },
  disabledButton: {
    opacity: 0.45,
  },
  noticeBanner: {
    borderRadius: 14,
    backgroundColor: '#e0f2fe',
    borderWidth: 1,
    borderColor: '#bae6fd',
    padding: 12,
  },
  amberBanner: {
    backgroundColor: '#fff7ed',
    borderColor: '#fed7aa',
  },
  noticeBannerText: {
    color: '#164e63',
    fontWeight: '700',
    lineHeight: 20,
  },
  helperText: {
    color: '#7a8a99',
    fontSize: 12,
    textAlign: 'center',
  },
  fieldHint: {
    color: '#6b7d8b',
    fontSize: 12,
    lineHeight: 18,
  },
  validationBox: {
    gap: 4,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#fecaca',
    backgroundColor: '#fff1f2',
    padding: 12,
  },
  validationText: {
    color: '#b91c1c',
    fontSize: 12,
    lineHeight: 18,
  },
  infoBox: {
    overflow: 'hidden',
    borderRadius: 16,
    backgroundColor: '#f7faf7',
    color: '#66746d',
    fontSize: 13,
    lineHeight: 20,
    padding: 14,
  },
  emptyText: {
    color: '#6b7d8b',
    padding: 14,
    borderRadius: 14,
    backgroundColor: '#ffffff',
    borderWidth: 1,
    borderColor: '#d6e5eb',
  },
  modalRoot: {
    flex: 1,
    backgroundColor: '#eef7fb',
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 12,
    padding: 18,
    borderBottomWidth: 1,
    borderBottomColor: '#d6e5eb',
  },
  modalTitle: {
    color: '#112d42',
    fontSize: 20,
    fontWeight: '900',
  },
  modalContent: {
    gap: 14,
    padding: 18,
    paddingBottom: 36,
  },
  periodRow: {
    flexDirection: 'row',
    gap: 10,
    alignItems: 'center',
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#e2edf2',
    padding: 12,
  },
  periodLabel: {
    color: '#112d42',
    fontWeight: '900',
  },
  flexOne: {
    flex: 1,
  },
  profileCard: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 12,
    borderRadius: 20,
    backgroundColor: '#dff3f5',
    padding: 18,
    borderWidth: 1,
    borderColor: '#c4e4e8',
  },
  metricGrid: {
    flexDirection: 'row',
    gap: 10,
  },
  metricCard: {
    flex: 1,
    borderRadius: 16,
    backgroundColor: '#ffffff',
    padding: 14,
    borderWidth: 1,
    borderColor: '#d6e5eb',
  },
  metricValue: {
    color: '#047c86',
    fontSize: 22,
    fontWeight: '900',
  },
  centerBox: {
    flex: 1,
    justifyContent: 'center',
    gap: 16,
    padding: 24,
  },
  centeredDialogContent: {
    gap: 12,
    alignItems: 'center',
    padding: 18,
  },
  dimmed: {
    flex: 1,
    justifyContent: 'center',
    backgroundColor: 'rgba(15, 35, 48, 0.35)',
    padding: 18,
  },
  dialog: {
    gap: 12,
    borderRadius: 22,
    backgroundColor: '#eef7fb',
    padding: 18,
    borderWidth: 1,
    borderColor: '#d6e5eb',
    maxHeight: '88%',
  },
  dialogForm: {
    gap: 14,
    paddingVertical: 4,
  },
  formGroup: {
    gap: 7,
  },
  formLabel: {
    color: '#31413b',
    fontSize: 14,
    fontWeight: '900',
  },
});

export default App;
