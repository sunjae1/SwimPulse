import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  ActivityIndicator,
  Alert,
  ImageBackground,
  Linking,
  Modal,
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleProp,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
  ViewStyle,
  useColorScheme,
} from 'react-native';
import Geolocation from 'react-native-geolocation-service';
import DateTimePicker, {
  DateTimePickerAndroid,
  type DateTimePickerEvent,
} from '@react-native-community/datetimepicker';
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
  getPoolLocationCandidates,
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
  EventStatus,
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
  formatMonthDescription,
  formatMonthLabel,
  formatShortPeriod,
  isEventClosed,
  isOcrInProgress,
  isPastPeriod,
  shiftPeriodToNextAvailableMonth,
  subscriptionKey,
  subscriptionKeyFromEvent,
  subscriptionTitle,
} from './src/utils/date';

const POOL_PAGE_SIZE = 10;
type SubscriptionStatusFilter = 'ALL' | EventStatus;

const SUBSCRIPTION_STATUS_FILTERS: Array<{value: SubscriptionStatusFilter; label: string}> = [
  {value: 'ALL', label: '전체'},
  {value: 'UPCOMING', label: '예정'},
  {value: 'OPEN', label: '시작'},
  {value: 'CLOSED', label: '종료'},
];

type RootStackParamList = {
  Home: undefined;
  MyPage:
    | {
        subscriptionId?: number;
        openDetail?: boolean;
        focusRequestId?: number;
      }
    | undefined;
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
    const fallback = pushFallback;
    const fallbackSubscriptionId = Number(fallback?.subscriptionId);
    const subscriptionId = notification?.subscriptionId ??
      (Number.isInteger(fallbackSubscriptionId) && fallbackSubscriptionId > 0 ? fallbackSubscriptionId : null);
    const openDetail =
      notification?.type === 'SOURCE_REVIEW_REQUIRED' || fallback?.type === 'SOURCE_REVIEW_REQUIRED';
    setPushNotification(null);
    setPushFallback(null);

    if (notification && !notification.readAt) {
      try {
        await markNotificationRead(notification.id);
      } catch (error) {
        showError('알림 읽음 처리에 실패했습니다.', error);
      }
    }

    if (subscriptionId && navigationRef.isReady()) {
      navigationRef.navigate('MyPage', {
        subscriptionId,
        openDetail,
        focusRequestId: Date.now(),
      });
    }
  }, [pushFallback, pushNotification]);

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
  const [locationSearching, setLocationSearching] = useState(false);
  const [selectingLocationTitle, setSelectingLocationTitle] = useState<string | null>(null);
  const [locationSearchFeedback, setLocationSearchFeedback] = useState<string | null>(null);
  const [nearbyOriginLabel, setNearbyOriginLabel] = useState('현재 위치');
  const [facilityCandidates, setFacilityCandidates] = useState<PoolLocationCandidate[]>([]);
  const [facilityCandidatesOpen, setFacilityCandidatesOpen] = useState(false);
  const [facilityCandidateToAdd, setFacilityCandidateToAdd] = useState<PoolLocationCandidate | null>(null);
  const [addingFacilityTitle, setAddingFacilityTitle] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyMessage, setBusyMessage] = useState<string | null>(null);
  const [scanningPoolId, setScanningPoolId] = useState<number | null>(null);
  const [scanResult, setScanResult] = useState<NoticeScanResponse | null>(null);
  const [scanVisible, setScanVisible] = useState(false);
  const [poolPage, setPoolPage] = useState(1);
  const [poolListMode, setPoolListMode] = useState<'NEARBY' | 'ALL'>('NEARBY');
  const [nearbyLoading, setNearbyLoading] = useState(false);
  const initialNearbyRequested = useRef(false);
  const isAdmin = user?.role === 'ADMIN';

  const subscribedKeys = useMemo(() => {
    return new Set(subscriptions.map(subscriptionKey));
  }, [subscriptions]);
  const poolListItems = useMemo(
    () =>
      poolListMode === 'NEARBY'
        ? nearbyPools
        : pools.map(pool => ({pool, distanceMeters: undefined})),
    [nearbyPools, poolListMode, pools],
  );
  const poolTotalPages = Math.max(1, Math.ceil(poolListItems.length / POOL_PAGE_SIZE));
  const safePoolPage = Math.min(poolPage, poolTotalPages);
  const visiblePoolItems = useMemo(() => {
    const startIndex = (safePoolPage - 1) * POOL_PAGE_SIZE;
    return poolListItems.slice(startIndex, startIndex + POOL_PAGE_SIZE);
  }, [poolListItems, safePoolPage]);

  const loadDashboard = useCallback(async () => {
    setLoading(true);
    try {
      const [poolResult, eventResult, subscriptionResult] = await Promise.all([
        getPools(),
        isAdmin ? getEvents() : Promise.resolve([] as RegistrationEvent[]),
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
  }, [isAdmin, user]);

  const requestNearbyPools = useCallback(async (options: {silent?: boolean} = {}) => {
    const silent = options.silent ?? false;
    try {
      setNearbyLoading(true);
      if (!silent) {
        setBusyMessage('현재 위치를 확인하고 있습니다.');
      }
      const granted = await requestLocationPermission();
      if (!granted) {
        setPoolListMode('ALL');
        setPoolPage(1);
        if (!silent) {
          Alert.alert('위치 권한이 필요합니다.', '주변 수영장 검색을 위해 위치 권한을 허용해 주세요.');
        }
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
            setNearbyOriginLabel('현재 위치');
            setFacilityCandidates([]);
            setFacilityCandidatesOpen(false);
            setPoolListMode('NEARBY');
            setPoolPage(1);
          } catch (error) {
            setPoolListMode('ALL');
            setPoolPage(1);
            if (!silent) {
              showError('주변 수영장을 불러오지 못했습니다.', error);
            }
          } finally {
            setNearbyLoading(false);
            setBusyMessage(null);
          }
        },
        error => {
          setPoolListMode('ALL');
          setPoolPage(1);
          setNearbyLoading(false);
          setBusyMessage(null);
          if (!silent) {
            Alert.alert('위치 확인 실패', error.message);
          }
        },
        {enableHighAccuracy: true, timeout: 10000, maximumAge: 60000},
      );
    } catch (error) {
      setPoolListMode('ALL');
      setPoolPage(1);
      setNearbyLoading(false);
      setBusyMessage(null);
      if (!silent) {
        showError('위치 권한 처리에 실패했습니다.', error);
      }
    }
  }, []);

  useEffect(() => {
    loadDashboard();
  }, [loadDashboard]);

  useEffect(() => {
    if (loading || pools.length === 0 || initialNearbyRequested.current) {
      return;
    }
    initialNearbyRequested.current = true;
    requestNearbyPools({silent: true}).catch(() => undefined);
  }, [loading, pools.length, requestNearbyPools]);

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
    if (scanningPoolId !== null) {
      return;
    }
    try {
      setScanningPoolId(pool.id);
      const result = await scanPoolNotices(pool.id);
      setScanResult(result);
      setScanVisible(true);
    } catch (error) {
      showError('공지 확인에 실패했습니다.', error);
    } finally {
      setScanningPoolId(null);
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
    const query = locationQuery.trim();
    if (!query || locationSearching) {
      if (!query) {
        setLocationSearchFeedback('검색할 장소 또는 주소를 입력해 주세요.');
      }
      return;
    }
    try {
      setLocationSearching(true);
      setLocationSearchFeedback(null);
      setFacilityCandidates([]);
      setFacilityCandidatesOpen(false);
      const results = await searchLocations(query, 8);
      setLocationResults(results);
      setLocationSearchFeedback(
        results.length > 0
          ? '검색 결과에서 위치를 선택하면 가까운 수영장 목록을 보여드려요.'
          : '검색 결과가 없습니다. 장소명이나 주소를 조금 다르게 입력해 주세요.',
      );
    } catch (error) {
      setLocationSearchFeedback('장소 검색에 실패했습니다. 잠시 후 다시 시도해 주세요.');
      showError('장소 검색에 실패했습니다.', error);
    } finally {
      setLocationSearching(false);
    }
  }

  async function selectLocationCandidate(candidate: LocationSearchCandidate) {
    if (!hasLocationCandidateCoordinates(candidate) || selectingLocationTitle) {
      return;
    }

    try {
      setSelectingLocationTitle(candidate.title);
      setFacilityCandidates([]);
      setFacilityCandidatesOpen(false);
      setLocationSearchFeedback(`${candidate.title} 기준 가까운 수영장과 추가 후보를 찾고 있습니다.`);
      const [nearby, facilities] = await Promise.all([
        getNearbyPools(candidate.latitude, candidate.longitude, 10),
        getPoolLocationCandidates(candidate.latitude, candidate.longitude, 5000, '체육센터', 10),
      ]);
      setNearbyPools(nearby);
      setNearbyOriginLabel(candidate.title);
      setFacilityCandidates(facilities.filter(item => !item.alreadyExists));
      setPoolListMode('NEARBY');
      setPoolPage(1);
      setLocationQuery(candidate.title);
      setLocationResults([]);
      setLocationSearchFeedback(
        `${candidate.title} 기준 가까운 수영장 ${nearby.length}개를 보여드려요.`,
      );
    } catch (error) {
      setLocationSearchFeedback('선택한 위치 기준 수영장을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
      showError('주변 수영장을 불러오지 못했습니다.', error);
    } finally {
      setSelectingLocationTitle(null);
    }
  }

  function showAllPools() {
    setPoolListMode('ALL');
    setPoolPage(1);
  }

  function requestPoolAdd(candidate: PoolLocationCandidate) {
    if (!user) {
      Alert.alert('로그인이 필요합니다.', '시설 추가 요청은 로그인 후 사용할 수 있습니다.');
      return;
    }
    setFacilityCandidateToAdd(candidate);
  }

  async function submitPoolAdd(candidate: PoolLocationCandidate) {
    try {
      setAddingFacilityTitle(candidate.title);
      await createPoolFromLocationCandidate(candidate);
      setFacilityCandidates(items => items.filter(item => item !== candidate));
      setFacilityCandidateToAdd(null);
      Alert.alert('요청 완료', '관리자가 검토 후 시설을 추가합니다.');
    } catch (error) {
      showError('시설 추가 요청에 실패했습니다.', error);
    } finally {
      setAddingFacilityTitle(null);
    }
  }

  const featuredEvents = events.slice(0, 5);

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.screenContent}>
        <ImageBackground
          source={require('./src/assets/pool-discovery-hero.png')}
          resizeMode="cover"
          imageStyle={styles.heroImage}
          style={styles.hero}>
          <View style={styles.heroOverlay}>
            <Text style={styles.heroEyebrow}>SWIMPULSE</Text>
            <Text style={styles.heroTitle}>가까운 수영장 접수를 놓치지 마세요.</Text>
            <Text style={styles.heroText}>
              위치를 찾고 공지의 모집 기간을 확인해 보세요.
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
        </ImageBackground>

        {booting || loading ? <ActivityIndicator color="#047c86" /> : null}
        {busyMessage ? <NoticeBanner text={busyMessage} /> : null}

        <Section title="위치 검색">
          <View style={styles.searchRow}>
            <TextInput
              value={locationQuery}
              onChangeText={value => {
                setLocationQuery(value);
                setLocationResults([]);
                setFacilityCandidates([]);
                setFacilityCandidatesOpen(false);
                setLocationSearchFeedback(null);
              }}
              placeholder="수원 수영장, 부천 체육센터"
              placeholderTextColor="#7a8a99"
              style={styles.searchInput}
              returnKeyType="search"
              onSubmitEditing={searchByKeyword}
            />
            <ActionButton label={locationSearching ? '검색 중...' : '검색'} onPress={searchByKeyword} disabled={locationSearching} />
          </View>
          {locationSearchFeedback ? <NoticeBanner text={locationSearchFeedback} /> : null}
          {locationResults.length > 0 ? (
            <View style={styles.locationResults}>
              <View style={styles.locationResultsHeader}>
                <View>
                  <Text style={styles.locationResultsEyebrow}>SEARCH RESULTS</Text>
                  <Text style={styles.locationResultsTitle}>어디를 기준으로 찾을까요?</Text>
                </View>
                <Text style={styles.locationResultsCount}>{locationResults.length}곳</Text>
              </View>
              {locationResults.map(candidate => (
                <CandidateCard
                  key={`${candidate.title}:${candidate.address}`}
                  candidate={candidate}
                  selecting={selectingLocationTitle === candidate.title}
                  selectionDisabled={selectingLocationTitle !== null && selectingLocationTitle !== candidate.title}
                  onSelect={() => selectLocationCandidate(candidate)}
                />
              ))}
            </View>
          ) : null}
          {facilityCandidates.length > 0 ? (
            <FacilityCandidateAccordion
              originLabel={nearbyOriginLabel}
              candidates={facilityCandidates}
              open={facilityCandidatesOpen}
              addingTitle={addingFacilityTitle}
              onToggle={() => setFacilityCandidatesOpen(open => !open)}
              onAdd={requestPoolAdd}
            />
          ) : null}
        </Section>

        <Section title="수영장 목록">
          <View style={styles.poolListHeader}>
            <Text style={styles.mutedText}>
              {poolListMode === 'NEARBY'
                ? nearbyLoading
                  ? '현재 위치를 기준으로 가까운 수영장을 찾고 있습니다.'
                  : `${nearbyOriginLabel} 기준 가까운 ${poolListItems.length.toLocaleString('ko-KR')}개`
                : `전체 ${pools.length.toLocaleString('ko-KR')}개 중 ${visiblePoolItems.length.toLocaleString('ko-KR')}개 표시`}
            </Text>
            <View style={styles.poolListModeRow}>
              <PoolListModeButton
                label="가까운 순"
                active={poolListMode === 'NEARBY'}
                disabled={nearbyLoading}
                onPress={() => requestNearbyPools()}
              />
              <PoolListModeButton label="전체 보기" active={poolListMode === 'ALL'} onPress={showAllPools} />
            </View>
          </View>
          {poolListMode === 'NEARBY' && !nearbyLoading && poolListItems.length === 0 ? (
            <EmptyText text="현재 위치 기준 가까운 수영장을 찾지 못했습니다. 전체 보기에서 모든 수영장을 확인할 수 있습니다." />
          ) : null}
          {visiblePoolItems.map(item => (
            <PoolCard
              key={item.pool.id}
              pool={item.pool}
              distanceMeters={item.distanceMeters}
              onScan={() => openNoticeScan(item.pool)}
              scanning={scanningPoolId === item.pool.id}
              scanDisabled={scanningPoolId !== null && scanningPoolId !== item.pool.id}
            />
          ))}
          {poolListItems.length > POOL_PAGE_SIZE ? (
            <MobilePaginationControls
              page={safePoolPage}
              totalPages={poolTotalPages}
              totalItems={poolListItems.length}
              pageSize={POOL_PAGE_SIZE}
              onPageChange={setPoolPage}
            />
          ) : null}
        </Section>

        {isAdmin ? (
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
        ) : null}
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
      <NoticeScanLoadingModal
        visible={scanningPoolId !== null}
        poolName={pools.find(pool => pool.id === scanningPoolId)?.name ?? nearbyPools.find(item => item.pool.id === scanningPoolId)?.pool.name ?? null}
      />
      <FacilityAddRequestModal
        candidate={facilityCandidateToAdd}
        submitting={addingFacilityTitle !== null}
        onClose={() => setFacilityCandidateToAdd(null)}
        onConfirm={submitPoolAdd}
      />
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
      const shifted = shiftPeriodToNextAvailableMonth(period.startsAt, period.endsAt);
      const targetMonth = formatMonthLabel(shifted.registrationStartsAt);
      const targetMonthDescription = formatMonthDescription(shifted.registrationStartsAt);
      const customTitle = `${notice.title} (${targetMonth} 사용자 지정)`.slice(0, 120);
      Alert.alert(
        '이미 지난 모집 기간입니다.',
        `${targetMonthDescription} 공지를 기다리거나, ${targetMonth} 같은 날짜에 사용자 지정 알림을 등록할까요?\n\n사용자 지정 기간: ${formatShortPeriod(
          shifted.registrationStartsAt,
          shifted.registrationEndsAt,
        )}\n\n주의: 실제 공지 알림이 아닌 임의로 지정한 사용자 알림이며, 실제 모집 일정과 다를 수 있습니다.`,
        [
          {text: '새 공지 기다리기', style: 'cancel'},
          {
            text: `${targetMonth} 날짜로 등록`,
            onPress: () =>
              submit({
                ...baseInput,
                title: customTitle,
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
            <EmptyText
              text={
                '현재 시설에서 확인 가능한 공지 경로를 찾지 못했습니다.\n시설 홈페이지의 공지 메뉴를 직접 확인하거나,\n나중에 다시 시도해 주세요.'
              }
            />
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
                      const shiftedPeriod = isPastPeriod(period.startsAt, period.endsAt)
                        ? shiftPeriodToNextAvailableMonth(period.startsAt, period.endsAt)
                        : null;
                      const shiftedEvent = shiftedPeriod
                        ? {
                            ...syntheticEvent,
                            noticeRegistrationPeriodId: null,
                            registrationStartsAt: shiftedPeriod.registrationStartsAt,
                            registrationEndsAt: shiftedPeriod.registrationEndsAt,
                          }
                        : null;
                      const existing =
                        subscribedByKey.get(subscriptionKeyFromEvent(syntheticEvent)) ??
                        (shiftedEvent
                          ? subscribedByKey.get(subscriptionKeyFromEvent(shiftedEvent))
                          : undefined);
                      const isWorking =
                        workingKey === `${notice.id}:${period.startsAt}:${period.endsAt}` ||
                        (shiftedPeriod &&
                          workingKey ===
                            `${notice.id}:${shiftedPeriod.registrationStartsAt}:${shiftedPeriod.registrationEndsAt}`) ||
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
}: AppState & {route: {params?: RootStackParamList['MyPage']}}) {
  const scrollViewRef = useRef<ScrollView>(null);
  const subscriptionSectionOffset = useRef(0);
  const subscriptionCardOffsets = useRef(new Map<number, number>());
  const highlightTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [data, setData] = useState<MyPageData | null>(null);
  const [notifications, setNotifications] = useState<InAppNotification[]>([]);
  const [notificationPage, setNotificationPage] = useState({page: 0, totalPages: 0, last: true});
  const [loading, setLoading] = useState(true);
  const [subscriptionStatusFilter, setSubscriptionStatusFilter] = useState<SubscriptionStatusFilter>('ALL');
  const [editing, setEditing] = useState<Subscription | null>(null);
  const [selectedSubscription, setSelectedSubscription] = useState<Subscription | null>(null);
  const [selectedNotification, setSelectedNotification] = useState<InAppNotification | null>(null);
  const [handledFocusRequest, setHandledFocusRequest] = useState<number | null>(null);
  const [pendingSubscriptionFocus, setPendingSubscriptionFocus] = useState<number | null>(null);
  const [highlightedSubscriptionId, setHighlightedSubscriptionId] = useState<number | null>(null);
  const [confirmingReview, setConfirmingReview] = useState(false);

  const scrollToSubscription = useCallback((subscriptionId: number) => {
    const cardOffset = subscriptionCardOffsets.current.get(subscriptionId);
    if (cardOffset === undefined) {
      setPendingSubscriptionFocus(subscriptionId);
      return;
    }

    scrollViewRef.current?.scrollTo({
      y: Math.max(0, subscriptionSectionOffset.current + cardOffset - 16),
      animated: true,
    });
    setPendingSubscriptionFocus(null);
    setHighlightedSubscriptionId(subscriptionId);

    if (highlightTimer.current) {
      clearTimeout(highlightTimer.current);
    }
    highlightTimer.current = setTimeout(() => {
      setHighlightedSubscriptionId(current => current === subscriptionId ? null : current);
    }, 4000);
  }, []);

  const focusSubscription = useCallback((subscriptionId: number) => {
    setSubscriptionStatusFilter('ALL');
    setHighlightedSubscriptionId(subscriptionId);
    setPendingSubscriptionFocus(subscriptionId);
    requestAnimationFrame(() => scrollToSubscription(subscriptionId));
  }, [scrollToSubscription]);

  useEffect(() => {
    return () => {
      if (highlightTimer.current) {
        clearTimeout(highlightTimer.current);
      }
    };
  }, []);

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
    const focusRequestId = route.params?.focusRequestId ?? targetId;
    if (!targetId || !focusRequestId || !data || handledFocusRequest === focusRequestId) {
      return;
    }
    const target = data.subscriptions.find(subscription => subscription.id === targetId);
    setHandledFocusRequest(focusRequestId);
    if (target) {
      focusSubscription(targetId);
      if (route.params?.openDetail) {
        setSelectedSubscription(target);
      }
    }
  }, [data, focusSubscription, handledFocusRequest, route.params]);

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

    if (!notification) {
      return;
    }

    if (!notification.readAt) {
      await readNotification(notification);
    }

    if (notification.subscriptionId) {
      const subscription = data?.subscriptions.find(candidate => candidate.id === notification.subscriptionId);
      if (subscription) {
        focusSubscription(notification.subscriptionId);
        if (notification.type === 'SOURCE_REVIEW_REQUIRED') {
          setSelectedSubscription(subscription);
        }
      }
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
  const subscriptions = data?.subscriptions ?? [];
  const filteredSubscriptions = [...subscriptions]
    .filter(subscription =>
      subscriptionStatusFilter === 'ALL' || subscription.event?.status === subscriptionStatusFilter,
    )
    .sort((left, right) => {
      const reviewOrder =
        Number(right.reviewStatus === 'REVIEW_REQUIRED') - Number(left.reviewStatus === 'REVIEW_REQUIRED');
      if (reviewOrder !== 0) {
        return reviewOrder;
      }
      return (left.event?.registrationStartsAt ?? '').localeCompare(right.event?.registrationStartsAt ?? '');
    });
  const subscriptionFilterCounts = SUBSCRIPTION_STATUS_FILTERS.reduce<Record<SubscriptionStatusFilter, number>>(
    (counts, option) => {
      counts[option.value] =
        option.value === 'ALL'
          ? subscriptions.length
          : subscriptions.filter(subscription => subscription.event?.status === option.value).length;
      return counts;
    },
    {ALL: 0, UPCOMING: 0, OPEN: 0, CLOSED: 0},
  );
  const selectedSubscriptionFilterLabel =
    SUBSCRIPTION_STATUS_FILTERS.find(option => option.value === subscriptionStatusFilter)?.label ?? '선택한';

  return (
    <Screen>
      <ScrollView ref={scrollViewRef} contentContainerStyle={styles.screenContent}>
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

        <View
          onLayout={event => {
            subscriptionSectionOffset.current = event.nativeEvent.layout.y;
          }}>
        <Section title="내 구독">
          <View style={styles.subscriptionFilterRow}>
            {SUBSCRIPTION_STATUS_FILTERS.map(option => (
              <SubscriptionStatusFilterButton
                key={option.value}
                label={option.label}
                count={subscriptionFilterCounts[option.value]}
                active={subscriptionStatusFilter === option.value}
                onPress={() => setSubscriptionStatusFilter(option.value)}
              />
            ))}
          </View>
          {subscriptions.length === 0 ? (
            <EmptyText text="아직 구독한 모집 기간이 없습니다." />
          ) : filteredSubscriptions.length === 0 ? (
            <EmptyText text={`${selectedSubscriptionFilterLabel} 상태의 구독이 없습니다.`} />
          ) : (
            filteredSubscriptions.map(subscription => (
              <View
                key={subscription.id}
                onLayout={event => {
                  subscriptionCardOffsets.current.set(subscription.id, event.nativeEvent.layout.y);
                  if (pendingSubscriptionFocus === subscription.id) {
                    requestAnimationFrame(() => scrollToSubscription(subscription.id));
                  }
                }}>
                <SubscriptionCard
                  subscription={subscription}
                  editable={!isEventClosed(subscription.event)}
                  highlighted={highlightedSubscriptionId === subscription.id}
                  onOpen={() => setSelectedSubscription(subscription)}
                  onEdit={isEventClosed(subscription.event) ? undefined : () => setEditing(subscription)}
                  onDelete={() => removeSubscription(subscription)}
                />
              </View>
            ))
          )}
        </Section>
        </View>

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
                {notification.type !== 'SOURCE_REVIEW_REQUIRED' && notification.registrationStartsAt ? (
                  <Text style={styles.notificationStartListText}>
                    접수 시작 {formatDateTime(notification.registrationStartsAt)}
                  </Text>
                ) : null}
                <Text style={styles.periodText}>알림 도착 {formatDateTime(notification.createdAt)}</Text>
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
  const [startsAt, setStartsAt] = useState(() => toPickerDate(event?.registrationStartsAt));
  const [endsAt, setEndsAt] = useState(() => toPickerDate(event?.registrationEndsAt));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    if (!event) {
      return;
    }

    if (startsAt.getTime() >= endsAt.getTime()) {
      setError('시작/종료 날짜와 시간을 다시 확인해 주세요.');
      return;
    }

    try {
      setSaving(true);
      await updateSubscriptionPeriod(subscription.id, {
        title,
        registrationStartsAt: startsAt.toISOString(),
        registrationEndsAt: endsAt.toISOString(),
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
                value={startsAt}
                onChange={setStartsAt}
                disabled={saving}
                hasError={Boolean(error)}
                onClearError={() => setError(null)}
              />
              <Text style={styles.fieldHint}>날짜와 시간을 눌러 달력과 시계에서 선택하세요. 한국 시간 기준입니다.</Text>
            </View>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>종료 시각</Text>
              <DateTimeEditor
                value={endsAt}
                onChange={setEndsAt}
                disabled={saving}
                hasError={Boolean(error)}
                onClearError={() => setError(null)}
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
  value: Date;
  onChange: (value: Date) => void;
  disabled: boolean;
  hasError: boolean;
  onClearError: () => void;
}) {
  const [iosMode, setIosMode] = useState<'date' | 'time' | null>(null);

  function applySelection(event: DateTimePickerEvent, selected?: Date) {
    if (event.type === 'set' && selected) {
      onChange(selected);
      onClearError();
    }
  }

  function openPicker(mode: 'date' | 'time') {
    if (disabled) {
      return;
    }
    if (Platform.OS === 'android') {
      DateTimePickerAndroid.open({
        value,
        mode,
        display: mode === 'date' ? 'calendar' : 'clock',
        is24Hour: true,
        timeZoneName: 'Asia/Seoul',
        onChange: applySelection,
      });
      return;
    }
    setIosMode(mode);
  }

  return (
    <>
      <View style={styles.dateTimeEditor}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="날짜 선택"
          disabled={disabled}
          onPress={() => openPicker('date')}
          style={[
            styles.datePickerButton,
            styles.datePickerDateButton,
            hasError ? styles.inputError : null,
            disabled ? styles.disabledButton : null,
          ]}>
          <Text style={styles.datePickerLabel}>날짜</Text>
          <Text style={styles.datePickerValue}>{formatPickerDate(value)}</Text>
        </Pressable>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="시간 선택"
          disabled={disabled}
          onPress={() => openPicker('time')}
          style={[
            styles.datePickerButton,
            styles.datePickerTimeButton,
            hasError ? styles.inputError : null,
            disabled ? styles.disabledButton : null,
          ]}>
          <Text style={styles.datePickerLabel}>시간</Text>
          <Text style={styles.datePickerValue}>{formatPickerTime(value)}</Text>
        </Pressable>
      </View>
      {Platform.OS === 'ios' && iosMode ? (
        <View style={styles.iosDatePickerPanel}>
          <DateTimePicker
            value={value}
            mode={iosMode}
            display="spinner"
            timeZoneName="Asia/Seoul"
            onChange={applySelection}
          />
          <SecondaryButton label="선택 완료" onPress={() => setIosMode(null)} />
        </View>
      ) : null}
    </>
  );
}

function toPickerDate(value: string | null | undefined) {
  const parsed = value ? new Date(value) : new Date();
  return Number.isNaN(parsed.getTime()) ? new Date() : parsed;
}

function formatPickerDate(value: Date) {
  return value.toLocaleDateString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });
}

function formatPickerTime(value: Date) {
  return value.toLocaleTimeString('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function PushNotificationModal({
  notification,
  fallback,
  loading,
  onClose,
}: {
  notification: InAppNotification | null;
  fallback: ReceivedPushMessage | null;
  loading: boolean;
  onClose: () => void;
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
  const registrationStartsAt = notification?.registrationStartsAt ?? fallback?.registrationStartsAt;
  const arrivedAt = notification?.createdAt ? formatDateTime(notification.createdAt) : null;
  const isUnread = notification ? !notification.readAt : true;

  return (
    <Modal
      visible={visible}
      animationType="fade"
      transparent
      onRequestClose={loading ? () => undefined : onClose}>
      <Pressable style={styles.dimmed} onPress={loading ? undefined : onClose}>
        <Pressable
          style={[styles.dialog, sourceReview ? styles.sourceReviewDialog : null]}
          onPress={event => event.stopPropagation()}>
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
              <Text style={styles.modalMessage}>{message}</Text>

              {sourceReview ? (
                <View style={styles.sourceReviewGuide}>
                  <Text style={styles.sourceReviewGuideTitle}>검토가 필요한 이유</Text>
                  <Text style={styles.sourceReviewGuideText}>
                    기존 공지는 이전 홈페이지에서 수집되었습니다. 원문과 새 홈페이지를 비교한 뒤 현재 기간을 유지하거나 수정해주세요.
                  </Text>
                </View>
              ) : poolName || eventTitle || registrationStartsAt || arrivedAt ? (
                <View style={styles.notificationPreview}>
                  {poolName ? <Text style={styles.noticeTitle}>{poolName}</Text> : null}
                  {eventTitle ? <Text style={styles.mutedText}>{eventTitle}</Text> : null}
                  {registrationStartsAt ? (
                    <View style={styles.notificationStartCard}>
                      <Text style={styles.notificationStartLabel}>접수 시작</Text>
                      <Text style={styles.notificationStartValue}>{formatDateTime(registrationStartsAt)}</Text>
                    </View>
                  ) : null}
                  {arrivedAt ? <Text style={styles.periodText}>알림 도착 {arrivedAt}</Text> : null}
                </View>
              ) : null}

              {sourceReview ? (
                <View style={styles.sourceReviewActions}>
                  <View style={styles.sourceReviewLinkRow}>
                    {noticeUrl ? (
                      <SecondaryButton
                        label="기존 공지 보기"
                        onPress={() => Linking.openURL(noticeUrl)}
                        style={styles.sourceReviewHalfButton}
                      />
                    ) : null}
                    {currentHomepageUrl ? (
                      <SecondaryButton
                        label="새 홈페이지 확인"
                        onPress={() => Linking.openURL(currentHomepageUrl)}
                        style={styles.sourceReviewHalfButton}
                      />
                    ) : null}
                  </View>
                  <SecondaryButton label="확인" onPress={onClose} style={styles.sourceReviewFullButton} />
                </View>
              ) : (
                <View style={styles.buttonRow}>
                  {noticeUrl ? <SecondaryButton label="원문 보기" onPress={() => Linking.openURL(noticeUrl)} /> : null}
                  <ActionButton label="확인" onPress={onClose} />
                </View>
              )}
              <Text style={styles.helperText}>
                {Number.isInteger(subscriptionId) && subscriptionId > 0
                  ? '확인하거나 바깥 영역을 누르면 해당 구독으로 이동합니다.'
                  : '확인하면 읽음 처리됩니다.'}
              </Text>
            </>
          )}
        </Pressable>
      </Pressable>
    </Modal>
  );
}

function NoticeScanLoadingModal({
  visible,
  poolName,
}: {
  visible: boolean;
  poolName: string | null;
}) {
  return (
    <Modal visible={visible} animationType="fade" transparent>
      <View style={styles.scanLoadingBackdrop}>
        <View style={styles.scanLoadingDialog}>
          <ActivityIndicator size="large" color="#047c86" />
          <Text style={styles.scanLoadingTitle}>공지 확인 중</Text>
          <Text style={styles.scanLoadingText}>
            {poolName ? `${poolName}의 홈페이지와 최근 공지를 확인하고 있습니다.` : '시설 홈페이지와 최근 공지를 확인하고 있습니다.'}
          </Text>
          <Text style={styles.scanLoadingHint}>이미지 공지는 분석 결과가 준비되면 이어서 안내합니다.</Text>
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
  scanning = false,
  scanDisabled = false,
}: {
  pool: Pool;
  distanceMeters?: number;
  onScan: () => void;
  scanning?: boolean;
  scanDisabled?: boolean;
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
        <ActionButton
          label={scanning ? '공지 확인 중...' : '공지 확인'}
          onPress={onScan}
          disabled={!canScanNotices || scanning || scanDisabled}
        />
      </View>
    </View>
  );
}

function CandidateCard({
  candidate,
  selecting,
  selectionDisabled,
  onSelect,
}: {
  candidate: LocationSearchCandidate;
  selecting: boolean;
  selectionDisabled: boolean;
  onSelect: () => void;
}) {
  const coordinatesUnavailable = !hasLocationCandidateCoordinates(candidate);
  const disabled = selectionDisabled || selecting || coordinatesUnavailable;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${candidate.title}을 기준 위치로 선택`}
      accessibilityState={{disabled}}
      disabled={disabled}
      onPress={onSelect}
      style={({pressed}) => [
        styles.locationCandidateCard,
        coordinatesUnavailable ? styles.locationCandidateCardUnavailable : null,
        pressed ? styles.locationCandidateCardPressed : null,
        selectionDisabled && !coordinatesUnavailable ? styles.disabledButton : null,
      ]}>
      <View style={styles.rowBetween}>
        <Text
          style={[
            styles.locationCandidateEyebrow,
            coordinatesUnavailable ? styles.locationCandidateEyebrowUnavailable : null,
          ]}>
          {coordinatesUnavailable ? '선택 불가' : '기준 위치'}
        </Text>
        <Text style={styles.locationCandidateCategory}>{candidate.category || '시설'}</Text>
      </View>
      <Text style={styles.locationCandidateTitle}>{candidate.title}</Text>
      <Text style={styles.locationCandidateAddress}>{candidate.roadAddress || candidate.address || '주소 정보 없음'}</Text>
      <Text
        style={[
          styles.locationCandidateAction,
          coordinatesUnavailable ? styles.locationCandidateWarning : null,
        ]}>
        {coordinatesUnavailable
          ? '위치 좌표를 확인하지 못했습니다. 다른 결과를 선택해주세요.'
          : selecting
            ? '가까운 수영장과 후보를 찾는 중...'
            : '눌러서 이 위치를 기준으로 선택'}
      </Text>
    </Pressable>
  );
}

function hasLocationCandidateCoordinates(candidate: LocationSearchCandidate): candidate is LocationSearchCandidate & {
  latitude: number;
  longitude: number;
} {
  return (
    candidate.latitude !== null &&
    candidate.longitude !== null &&
    Number.isFinite(candidate.latitude) &&
    Number.isFinite(candidate.longitude) &&
    Math.abs(candidate.latitude) <= 90 &&
    Math.abs(candidate.longitude) <= 180
  );
}

function FacilityAddRequestModal({
  candidate,
  submitting,
  onClose,
  onConfirm,
}: {
  candidate: PoolLocationCandidate | null;
  submitting: boolean;
  onClose: () => void;
  onConfirm: (candidate: PoolLocationCandidate) => void;
}) {
  if (!candidate) {
    return null;
  }

  const address = candidate.roadAddress || candidate.address || '주소 정보 없음';

  return (
    <Modal visible animationType="fade" transparent onRequestClose={submitting ? () => undefined : onClose}>
      <View style={styles.dimmed}>
        <View style={[styles.dialog, styles.facilityAddDialog]}>
          <View style={styles.rowBetween}>
            <Text style={styles.facilityAddEyebrow}>FACILITY REQUEST</Text>
            <Text style={styles.facilityCandidateBadge}>추가 후보</Text>
          </View>
          <Text style={styles.modalTitle}>이 시설을 추가할까요?</Text>
          <Text style={styles.modalMessage}>
            아직 SwimPulse에 등록되지 않은 시설입니다. 요청을 보내면 관리자가 확인한 뒤 목록에 반영합니다.
          </Text>

          <View style={styles.facilityAddPreview}>
            <Text style={styles.facilityAddPreviewLabel}>추가 요청 시설</Text>
            <Text style={styles.facilityAddPreviewTitle}>{candidate.title}</Text>
            <Text style={styles.facilityAddPreviewAddress}>{address}</Text>
            <View style={styles.facilityCandidateMetaRow}>
              {candidate.category ? <Text style={styles.facilityCandidateMeta}>{candidate.category}</Text> : null}
              {candidate.distanceMeters !== null ? (
                <Text style={styles.facilityCandidateDistance}>{formatDistance(candidate.distanceMeters)}</Text>
              ) : null}
            </View>
          </View>

          <Text style={styles.helperText}>이미 등록된 시설은 요청할 수 없으며, 중복 여부는 서버에서 다시 확인합니다.</Text>
          <View style={styles.facilityAddActions}>
            <SecondaryButton label="취소" onPress={onClose} disabled={submitting} style={styles.facilityAddHalfButton} />
            <ActionButton
              label={submitting ? '요청 중...' : '요청하기'}
              onPress={() => onConfirm(candidate)}
              disabled={submitting}
              style={styles.facilityAddHalfButton}
            />
          </View>
        </View>
      </View>
    </Modal>
  );
}

function FacilityCandidateAccordion({
  originLabel,
  candidates,
  open,
  addingTitle,
  onToggle,
  onAdd,
}: {
  originLabel: string;
  candidates: PoolLocationCandidate[];
  open: boolean;
  addingTitle: string | null;
  onToggle: () => void;
  onAdd: (candidate: PoolLocationCandidate) => void;
}) {
  return (
    <View style={styles.facilityCandidatePanel}>
      <Pressable
        accessibilityRole="button"
        accessibilityState={{expanded: open}}
        onPress={onToggle}
        style={({pressed}) => [styles.facilityCandidateToggle, pressed ? styles.locationCandidateCardPressed : null]}>
        <View style={styles.flexOne}>
          <Text style={styles.facilityCandidateToggleTitle}>DB에 없는 수영장 후보 {candidates.length}곳</Text>
          <Text style={styles.facilityCandidateToggleText}>{originLabel} 주변 검색 결과</Text>
        </View>
        <Text style={styles.facilityCandidateToggleAction}>{open ? '접기' : '보기'}</Text>
      </Pressable>

      {open ? (
        <View style={styles.facilityCandidateList}>
          <Text style={styles.facilityCandidateGuide}>
            {'아직 SwimPulse에 등록되지 않은 시설입니다.\n필요한 시설만 추가 요청해주세요.\n 관리자 검토 후 승인/반려 될 수 있습니다.'}
          </Text>
          {candidates.map((candidate, index) => {
            const address = candidate.roadAddress || candidate.address || '주소 정보 없음';
            const adding = addingTitle === candidate.title;
            return (
              <View key={`${candidate.title}:${address}:${index}`} style={styles.facilityCandidateCard}>
                <View style={styles.rowBetween}>
                  <Text style={styles.facilityCandidateTitle}>{candidate.title}</Text>
                  <Text style={styles.facilityCandidateBadge}>추가 가능</Text>
                </View>
                <Text style={styles.facilityCandidateAddress}>{address}</Text>
                <View style={styles.facilityCandidateMetaRow}>
                  {candidate.category ? <Text style={styles.facilityCandidateMeta}>{candidate.category}</Text> : null}
                  {candidate.distanceMeters !== null ? (
                    <Text style={styles.facilityCandidateDistance}>{formatDistance(candidate.distanceMeters)}</Text>
                  ) : null}
                </View>
                <ActionButton
                  label={adding ? '요청 중...' : '이 시설 추가'}
                  onPress={() => onAdd(candidate)}
                  disabled={addingTitle !== null}
                  style={styles.facilityCandidateAddButton}
                />
              </View>
            );
          })}
        </View>
      ) : null}
    </View>
  );
}

function SubscriptionCard({
  subscription,
  editable,
  highlighted,
  onOpen,
  onEdit,
  onDelete,
}: {
  subscription: Subscription;
  editable: boolean;
  highlighted: boolean;
  onOpen: () => void;
  onEdit?: () => void;
  onDelete: () => void;
}) {
  const event = subscription.event;
  const needsReview = subscription.reviewStatus === 'REVIEW_REQUIRED';
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{selected: highlighted}}
      onPress={onOpen}
      style={[
        styles.poolCard,
        needsReview ? styles.reviewSubscriptionCard : null,
        highlighted ? styles.highlightedSubscriptionCard : null,
      ]}>
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
  style,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <TouchableOpacity
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={[styles.actionButton, style, disabled ? styles.disabledButton : null]}>
      <Text style={styles.actionButtonText}>{label}</Text>
    </TouchableOpacity>
  );
}

function SecondaryButton({
  label,
  onPress,
  disabled,
  style,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={[styles.secondaryButton, style, disabled ? styles.disabledButton : null]}>
      <Text style={styles.secondaryButtonText}>{label}</Text>
    </Pressable>
  );
}

function PoolListModeButton({
  label,
  active,
  disabled,
  onPress,
}: {
  label: string;
  active: boolean;
  disabled?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{selected: active, disabled}}
      disabled={disabled}
      onPress={onPress}
      style={[
        styles.poolListModeButton,
        active ? styles.poolListModeButtonActive : null,
        disabled ? styles.disabledButton : null,
      ]}>
      <Text style={[styles.poolListModeButtonText, active ? styles.poolListModeButtonTextActive : null]}>
        {label}
      </Text>
    </Pressable>
  );
}

function SubscriptionStatusFilterButton({
  label,
  count,
  active,
  onPress,
}: {
  label: string;
  count: number;
  active: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{selected: active}}
      onPress={onPress}
      style={[styles.subscriptionFilterButton, active ? styles.subscriptionFilterButtonActive : null]}>
      <Text style={[styles.subscriptionFilterText, active ? styles.subscriptionFilterTextActive : null]}>
        {label}
      </Text>
      <Text style={[styles.subscriptionFilterCount, active ? styles.subscriptionFilterCountActive : null]}>
        {count}
      </Text>
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

function formatDistance(distanceMeters: number) {
  if (distanceMeters >= 1000) {
    return `${(distanceMeters / 1000).toFixed(1)}km`;
  }
  return `${Math.round(distanceMeters)}m`;
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
    minHeight: 270,
    borderRadius: 20,
    overflow: 'hidden',
    backgroundColor: '#1f6f7a',
  },
  heroImage: {
    borderRadius: 20,
  },
  heroOverlay: {
    flex: 1,
    justifyContent: 'flex-end',
    gap: 12,
    padding: 20,
    backgroundColor: 'rgba(6, 63, 70, 0.68)',
  },
  heroEyebrow: {
    color: '#a7f3d0',
    fontSize: 12,
    fontWeight: '900',
    letterSpacing: 1.4,
  },
  eyebrow: {
    color: '#047c86',
    fontSize: 12,
    fontWeight: '800',
  },
  heroTitle: {
    color: '#ffffff',
    fontSize: 24,
    fontWeight: '900',
  },
  heroText: {
    color: '#e2f5f7',
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
  poolListHeader: {
    gap: 10,
  },
  poolListModeRow: {
    flexDirection: 'row',
    gap: 8,
  },
  poolListModeButton: {
    flex: 1,
    minHeight: 42,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#b7ced9',
    backgroundColor: '#ffffff',
    paddingHorizontal: 12,
  },
  poolListModeButtonActive: {
    borderColor: '#047c86',
    backgroundColor: '#047c86',
  },
  poolListModeButtonText: {
    color: '#244a5f',
    fontWeight: '900',
  },
  poolListModeButtonTextActive: {
    color: '#ffffff',
  },
  subscriptionFilterRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 7,
  },
  subscriptionFilterButton: {
    minWidth: 70,
    minHeight: 42,
    flexGrow: 1,
    flexBasis: '22%',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 5,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#b7ced9',
    backgroundColor: '#ffffff',
    paddingHorizontal: 9,
  },
  subscriptionFilterButtonActive: {
    borderColor: '#047c86',
    backgroundColor: '#047c86',
  },
  subscriptionFilterText: {
    color: '#244a5f',
    fontSize: 14,
    fontWeight: '900',
  },
  subscriptionFilterTextActive: {
    color: '#ffffff',
  },
  subscriptionFilterCount: {
    color: '#6b879c',
    fontSize: 12,
    fontWeight: '900',
  },
  subscriptionFilterCountActive: {
    color: '#d9fffa',
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
  datePickerButton: {
    minHeight: 64,
    justifyContent: 'center',
    gap: 4,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#b7ced9',
    backgroundColor: '#ffffff',
    paddingHorizontal: 14,
  },
  datePickerDateButton: {
    flex: 1,
  },
  datePickerTimeButton: {
    minWidth: 118,
  },
  datePickerLabel: {
    color: '#5c7080',
    fontSize: 12,
    fontWeight: '800',
  },
  datePickerValue: {
    color: '#244a5f',
    fontSize: 15,
    fontWeight: '900',
  },
  iosDatePickerPanel: {
    gap: 8,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#d6e5eb',
    backgroundColor: '#ffffff',
    padding: 10,
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
  locationResults: {
    gap: 12,
    borderRadius: 18,
    backgroundColor: '#ffffff',
    borderWidth: 1,
    borderColor: '#7dd3fc',
    borderTopWidth: 4,
    borderTopColor: '#0284c7',
    padding: 10,
    elevation: 2,
  },
  locationResultsHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    borderRadius: 13,
    backgroundColor: '#075985',
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  locationResultsEyebrow: {
    color: '#bae6fd',
    fontSize: 11,
    fontWeight: '900',
    letterSpacing: 0,
  },
  locationResultsTitle: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '900',
  },
  locationResultsCount: {
    overflow: 'hidden',
    borderRadius: 12,
    backgroundColor: '#e0f2fe',
    color: '#075985',
    paddingHorizontal: 9,
    paddingVertical: 5,
    fontSize: 12,
    fontWeight: '900',
  },
  locationCandidateCard: {
    gap: 9,
    borderRadius: 14,
    backgroundColor: '#f8fcff',
    borderWidth: 1,
    borderColor: '#d2eaf7',
    padding: 14,
  },
  locationCandidateCardPressed: {
    backgroundColor: '#e0f2fe',
    borderColor: '#38bdf8',
  },
  locationCandidateCardUnavailable: {
    backgroundColor: '#f4f5f3',
    borderColor: '#d5d9d6',
  },
  locationCandidateEyebrow: {
    color: '#0369a1',
    fontSize: 11,
    fontWeight: '900',
    letterSpacing: 0,
  },
  locationCandidateEyebrowUnavailable: {
    color: '#68716b',
  },
  locationCandidateCategory: {
    overflow: 'hidden',
    maxWidth: '62%',
    borderRadius: 10,
    backgroundColor: '#e0f2fe',
    color: '#075985',
    paddingHorizontal: 7,
    paddingVertical: 3,
    fontSize: 11,
    fontWeight: '800',
  },
  locationCandidateTitle: {
    color: '#0c4a6e',
    fontSize: 17,
    fontWeight: '900',
  },
  locationCandidateAddress: {
    color: '#365b70',
    fontSize: 13,
    lineHeight: 19,
  },
  locationCandidateAction: {
    color: '#0369a1',
    fontSize: 13,
    fontWeight: '900',
  },
  locationCandidateWarning: {
    color: '#a04b3d',
  },
  facilityCandidatePanel: {
    overflow: 'hidden',
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#f1c27d',
    backgroundColor: '#fffaf3',
  },
  facilityCandidateToggle: {
    minHeight: 64,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 14,
    paddingVertical: 11,
  },
  facilityCandidateToggleTitle: {
    color: '#7c4a12',
    fontSize: 15,
    fontWeight: '900',
  },
  facilityCandidateToggleText: {
    color: '#8a6a45',
    fontSize: 12,
    lineHeight: 18,
  },
  facilityCandidateToggleAction: {
    overflow: 'hidden',
    borderRadius: 10,
    backgroundColor: '#ffedd5',
    color: '#9a3412',
    paddingHorizontal: 10,
    paddingVertical: 6,
    fontSize: 12,
    fontWeight: '900',
  },
  facilityCandidateList: {
    gap: 10,
    borderTopWidth: 1,
    borderTopColor: '#fed7aa',
    padding: 12,
  },
  facilityCandidateGuide: {
    color: '#7c5b36',
    fontSize: 12,
    lineHeight: 18,
  },
  facilityCandidateCard: {
    gap: 8,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#fed7aa',
    backgroundColor: '#ffffff',
    padding: 13,
  },
  facilityCandidateTitle: {
    flex: 1,
    color: '#3f2a14',
    fontSize: 15,
    fontWeight: '900',
  },
  facilityCandidateBadge: {
    overflow: 'hidden',
    borderRadius: 10,
    backgroundColor: '#ffedd5',
    color: '#9a3412',
    paddingHorizontal: 7,
    paddingVertical: 3,
    fontSize: 11,
    fontWeight: '900',
  },
  facilityCandidateAddress: {
    color: '#6f6254',
    fontSize: 13,
    lineHeight: 19,
  },
  facilityCandidateMetaRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: 6,
  },
  facilityCandidateMeta: {
    color: '#0f766e',
    fontSize: 12,
    fontWeight: '700',
  },
  facilityCandidateDistance: {
    overflow: 'hidden',
    borderRadius: 9,
    backgroundColor: '#f1f5f9',
    color: '#526473',
    paddingHorizontal: 7,
    paddingVertical: 3,
    fontSize: 11,
    fontWeight: '800',
  },
  facilityCandidateAddButton: {
    alignSelf: 'stretch',
    backgroundColor: '#c65d16',
  },
  reviewSubscriptionCard: {
    borderColor: '#fdba74',
    backgroundColor: '#fffaf3',
  },
  highlightedSubscriptionCard: {
    borderWidth: 2,
    borderColor: '#0284c7',
    backgroundColor: '#e0f2fe',
    shadowColor: '#0284c7',
    shadowOffset: {width: 0, height: 3},
    shadowOpacity: 0.22,
    shadowRadius: 8,
    elevation: 4,
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
  notificationStartCard: {
    gap: 3,
    marginTop: 6,
    borderRadius: 12,
    backgroundColor: '#e9f7f5',
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderWidth: 1,
    borderColor: '#acdcd5',
  },
  notificationStartLabel: {
    color: '#047c86',
    fontSize: 12,
    fontWeight: '900',
  },
  notificationStartValue: {
    color: '#112d42',
    fontSize: 16,
    fontWeight: '900',
  },
  notificationStartListText: {
    color: '#047c86',
    fontSize: 14,
    fontWeight: '900',
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
  modalMessage: {
    color: '#3f596c',
    fontSize: 15,
    lineHeight: 22,
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
  scanLoadingBackdrop: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(15, 35, 48, 0.45)',
    padding: 28,
  },
  scanLoadingDialog: {
    width: '100%',
    maxWidth: 360,
    alignItems: 'center',
    gap: 10,
    borderRadius: 20,
    backgroundColor: '#ffffff',
    padding: 24,
  },
  scanLoadingTitle: {
    color: '#112d42',
    fontSize: 19,
    fontWeight: '900',
  },
  scanLoadingText: {
    color: '#315a61',
    fontSize: 14,
    lineHeight: 21,
    textAlign: 'center',
  },
  scanLoadingHint: {
    color: '#6b7f8d',
    fontSize: 13,
    lineHeight: 19,
    textAlign: 'center',
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
  sourceReviewDialog: {
    gap: 14,
    backgroundColor: '#ffffff',
    padding: 16,
  },
  facilityAddDialog: {
    gap: 14,
    backgroundColor: '#ffffff',
    borderColor: '#b9e8e3',
  },
  facilityAddEyebrow: {
    color: '#047c86',
    fontSize: 11,
    fontWeight: '900',
    letterSpacing: 0,
  },
  facilityAddPreview: {
    gap: 7,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#b9e8e3',
    backgroundColor: '#f0fdfa',
    padding: 14,
  },
  facilityAddPreviewLabel: {
    color: '#047c86',
    fontSize: 12,
    fontWeight: '900',
  },
  facilityAddPreviewTitle: {
    color: '#123b40',
    fontSize: 17,
    fontWeight: '900',
  },
  facilityAddPreviewAddress: {
    color: '#42636a',
    fontSize: 13,
    lineHeight: 19,
  },
  facilityAddActions: {
    flexDirection: 'row',
    gap: 8,
  },
  facilityAddHalfButton: {
    flex: 1,
    paddingHorizontal: 8,
  },
  sourceReviewGuide: {
    gap: 6,
    borderRadius: 14,
    backgroundColor: '#f0fdfa',
    borderWidth: 1,
    borderColor: '#b9e8e3',
    padding: 13,
  },
  sourceReviewGuideTitle: {
    color: '#047c86',
    fontSize: 13,
    fontWeight: '900',
  },
  sourceReviewGuideText: {
    color: '#315a61',
    fontSize: 13,
    lineHeight: 19,
  },
  sourceReviewActions: {
    gap: 8,
  },
  sourceReviewLinkRow: {
    flexDirection: 'row',
    gap: 8,
  },
  sourceReviewHalfButton: {
    flex: 1,
    paddingHorizontal: 8,
  },
  sourceReviewFullButton: {
    alignSelf: 'stretch',
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
