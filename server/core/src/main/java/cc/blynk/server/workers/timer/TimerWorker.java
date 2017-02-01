package cc.blynk.server.workers.timer;

import cc.blynk.server.core.dao.SessionDao;
import cc.blynk.server.core.dao.UserDao;
import cc.blynk.server.core.dao.UserKey;
import cc.blynk.server.core.model.DashBoard;
import cc.blynk.server.core.model.auth.Session;
import cc.blynk.server.core.model.auth.User;
import cc.blynk.server.core.model.widgets.Widget;
import cc.blynk.server.core.model.widgets.controls.Timer;
import cc.blynk.server.core.model.widgets.others.eventor.TimerTime;
import cc.blynk.server.core.model.widgets.others.eventor.model.action.SetPinAction;
import cc.blynk.utils.ArrayUtil;
import cc.blynk.utils.DateTimeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static cc.blynk.server.core.protocol.enums.Command.HARDWARE;

/**
 * Timer worker class responsible for triggering all timers at specified time.
 * Current implementation is some kind of Hashed Wheel Timer.
 * In general idea is very simple :
 *
 * Select timers at specified cell timer[secondsOfDayNow]
 * and run it one by one, instead of naive implementation
 * with iteration over all profiles every second
 *
 * + Concurrency around it as timerWorker may be accessed from different threads.
 *
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/6/2015.
 *
 */
public class TimerWorker implements Runnable {

    private static final Logger log = LogManager.getLogger(TimerWorker.class);
    public static final int TIMER_MSG_ID = 7777;

    private final UserDao userDao;
    private final SessionDao sessionDao;
    //todo refactor after migration
    private final ConcurrentMap<TimerKey, Object>[] timerExecutors;
    private final static int size = 8640;

    @SuppressWarnings("unchecked")
    public TimerWorker(UserDao userDao, SessionDao sessionDao) {
        this.userDao = userDao;
        this.sessionDao = sessionDao;
        //array cell for every second in a day,
        //yes, it costs a bit of memory, but still cheap :)
        this.timerExecutors = new ConcurrentMap[size];
        for (int i = 0; i < size; i++) {
            timerExecutors[i] = new ConcurrentHashMap<>();
        }
        init(userDao.users);
    }

    private static int hash(int time) {
        return time / 10;
    }

    private void init(ConcurrentMap<UserKey, User> users) {
        int counter = 0;
        for (Map.Entry<UserKey, User> entry : users.entrySet()) {
            for (DashBoard dashBoard : entry.getValue().profile.dashBoards) {
                for (Widget widget : dashBoard.widgets) {
                    if (widget instanceof Timer) {
                        Timer timer = (Timer) widget;
                        add(entry.getKey(), timer, dashBoard.id);
                        counter++;
                    }
                }
            }
        }
        log.info("Timers : {}", counter);
    }

    public void add(UserKey userKey, Timer timer, int dashId) {
        if (timer.isValidStart()) {
            add(userKey, dashId, timer.deviceId, timer.id, 0, new TimerTime(timer.startTime), timer.startValue);
        }
        if (timer.isValidStop()) {
            add(userKey, dashId, timer.deviceId, timer.id, 1, new TimerTime(timer.stopTime), timer.stopValue);
        }
    }

    public void add(UserKey userKey, int dashId, int deviceId, long widgetId, int additionalId, TimerTime time, Object value) {
        timerExecutors[hash(time.time)].put(new TimerKey(userKey, dashId, deviceId, widgetId, additionalId, time), value);
    }

    public void delete(UserKey userKey, Timer timer, int dashId) {
        if (timer.isValidStart()) {
            delete(userKey, dashId, timer.deviceId, timer.id, 0, new TimerTime(timer.startTime));
        }
        if (timer.isValidStop()) {
            delete(userKey, dashId, timer.deviceId, timer.id, 1, new TimerTime(timer.stopTime));
        }
    }

    public void delete(UserKey userKey, int dashId, int deviceId, long widgetId, int additionalId, TimerTime time) {
        timerExecutors[hash(time.time)].remove(new TimerKey(userKey, dashId, deviceId, widgetId, additionalId, time));
    }

    private int actuallySendTimers;

    @Override
    public void run() {
        log.trace("Starting timer...");

        final ZonedDateTime currentDateTime = ZonedDateTime.now(DateTimeUtils.UTC);
        final int curSeconds = currentDateTime.toLocalTime().toSecondOfDay();

        ConcurrentMap<TimerKey, ?> tickedExecutors = timerExecutors[hash(curSeconds)];

        int readyForTickTimers = tickedExecutors.size();
        if (readyForTickTimers == 0) {
            return;
        }

        final long nowMillis = System.currentTimeMillis();
        int activeTimers = 0;

        try {
            activeTimers = send(tickedExecutors, currentDateTime, curSeconds, nowMillis);
        } catch (Exception e) {
            log.error("Error running timers. ", e);
        }

        if (activeTimers > 0) {
            log.info("Timer finished. Ready {}, Active {}, Actual {}. Processing time : {} ms",
                    readyForTickTimers, activeTimers, actuallySendTimers, System.currentTimeMillis() - nowMillis);
        }
    }

    private int send(ConcurrentMap<TimerKey, ?> tickedExecutors, ZonedDateTime currentDateTime, int curSeconds, long nowMillis) {
        int activeTimers = 0;
        actuallySendTimers = 0;

        for (Map.Entry<TimerKey, ?> entry : tickedExecutors.entrySet()) {
            final TimerKey key = entry.getKey();
            final Object objValue = entry.getValue();
            if (key.time.time == curSeconds && isTime(key.time, currentDateTime)) {
                User user = userDao.users.get(key.userKey);
                if (user != null) {
                    DashBoard dash = user.profile.getDashById(key.dashId);
                    if (dash != null && dash.isActive) {
                        activeTimers++;
                        String value;
                        //todo remove after migration
                        if (objValue instanceof String) {
                            value = (String) objValue;
                            try {
                                Timer timer = (Timer) dash.getWidgetById(key.widgetId);
                                timer.value = value;
                            } catch (Exception e) {
                                //ignore. this code should be removed anyway, after migration.
                            }
                        } else {
                            SetPinAction setPinAction = (SetPinAction) objValue;
                            value = setPinAction.makeHardwareBody();
                            dash.update(key.deviceId, setPinAction.pin.pin, setPinAction.pin.pinType, setPinAction.value, nowMillis);
                        }
                        triggerTimer(sessionDao, key.userKey, value, key.dashId, key.deviceId);
                    }
                }
            }
        }

        return activeTimers;
    }

    private boolean isTime(TimerTime timerTime, ZonedDateTime currentDateTime) {
        LocalDateTime userDateTime = currentDateTime.withZoneSameInstant(timerTime.tzName).toLocalDateTime();
        final int dayOfWeek = userDateTime.getDayOfWeek().ordinal() + 1;
        return ArrayUtil.contains(timerTime.days, dayOfWeek);
    }

    private void triggerTimer(SessionDao sessionDao, UserKey userKey, String value, int dashId, int deviceId) {
        Session session = sessionDao.userSession.get(userKey);
        if (session != null) {
            if (!session.sendMessageToHardware(dashId, HARDWARE, TIMER_MSG_ID, value, deviceId)) {
                actuallySendTimers++;
            }
            session.sendToApps(HARDWARE, TIMER_MSG_ID, dashId, deviceId, value);
        }
    }

}
