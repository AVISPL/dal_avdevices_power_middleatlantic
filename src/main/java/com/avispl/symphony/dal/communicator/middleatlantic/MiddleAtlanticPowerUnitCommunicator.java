package com.avispl.symphony.dal.communicator.middleatlantic;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.google.common.collect.ImmutableMap;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static java.util.concurrent.CompletableFuture.runAsync;

public class MiddleAtlanticPowerUnitCommunicator extends RestCommunicator implements Monitorable, Controller {

    private static final String OUTLET = "Outlet";
    private static final String BASE_URI = "model/pdu/0";
    private static final String GET_READING = "getReading";

    /**
     * MiddleAtlanticPowerUnit constructor.
     */
    public MiddleAtlanticPowerUnitCommunicator() {
        super();
        // override default value to trust all certificates - power unit typically do not have trusted certificates installed
        // it can be changed back by configuration
        setTrustAllCertificates(true);
    }

    @Override
    protected void authenticate() throws Exception {
        // nothing to do here, authentication is done in individual requests
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();
        Map<String, String> statistics = Collections.synchronizedMap(new LinkedHashMap<>());
        Map<String, String> control = new ConcurrentHashMap<>();

        ExecutorService executor = Executors.newCachedThreadPool();
        runAsync(() -> fillInStatistics(statistics, "Inlet Active Power", "/inlet/0/activePower", GET_READING), executor)
                .thenRunAsync(() -> fillInStatistics(statistics, "Inlet RMS Current", "/inlet/0/current", GET_READING), executor)
                .thenRunAsync(() -> fillInStatistics(statistics, "Inlet Line Frequency", "/inlet/0/lineFrequency", GET_READING), executor)
                .thenRunAsync(() -> fillInStatistics(statistics, "Inlet RMS Voltage", "/inlet/0/voltage", GET_READING), executor)
                .thenApplyAsync(ignore -> getOutletsCount(), executor)
                .thenAcceptAsync(count -> IntStream.range(0, count)
                        .parallel()
                        .mapToObj(num -> runAsync(() -> fillInOutletState(statistics, control, num), executor)
                                .thenRunAsync(() -> fillInStatistics(statistics, getOutletRMSName(num),
                                        "/outlet/" + num + "/current", GET_READING), executor)
                        )
                        .forEach(CompletableFuture::join), executor)
                .get(30, TimeUnit.SECONDS);

        executor.shutdownNow();

        extendedStatistics.setStatistics(statistics);
        extendedStatistics.setControl(control);
        return new ArrayList<>(Arrays.asList(extendedStatistics));
    }

    /**
     * @return -1 if host is not reachable within
     * the pingTimeout, a ping time in milliseconds otherwise
     * if ping is 0ms it's rounded up to 1ms to avoid IU issues on Symphony portal
     * @throws IOException
     */
    @Override
    public int ping() throws IOException {
        long pingResultTotal = 0L;

            for(int i = 0; i < this.getPingAttempts(); i++) {
                long startTime = System.currentTimeMillis();

                try(Socket puSocketConnection = new Socket(this.getHost(), this.getPort())) {
                        puSocketConnection.setSoTimeout(this.getPingTimeout());

                        if (puSocketConnection.isConnected()) {
                            long endTime = System.currentTimeMillis();
                            long pingResult = endTime - startTime;
                            pingResultTotal += pingResult;
                            if (this.logger.isTraceEnabled()) {
                                this.logger.trace(String.format("PING OK: Attempt #%s to connect to %s on port %s succeeded in %s ms", i + 1, this.getHost(), this.getPort(), pingResult));
                            }
                        } else {
                            if (this.logger.isDebugEnabled()) {
                                this.logger.debug(String.format("PING DISCONNECTED: Connection to %s did not succeed within the timeout period of %sms", this.getHost(), this.getPingTimeout()));
                            }
                            return -1;
                        }
                } catch (SocketTimeoutException tex){
                    if (this.logger.isDebugEnabled()) {
                        this.logger.debug(String.format("PING TIMEOUT: Connection to %s did not succeed within the timeout period of %sms", this.getHost(), this.getPingTimeout()));
                    }
                    return -1;
                }
            }
        return Math.max(1, Math.toIntExact(pingResultTotal / this.getPingAttempts()));
    }

    private int getOutletsCount() {
        try {
            Map result = doPost("model/outlet", null, Map.class);
            return (int) ((Map) ((Map) result.get("result")).get("_ret_")).get("numberOfOutlets");
        } catch (Exception e) {
            throw new RuntimeException("Method doesn't not work at the URI " + BASE_URI + "/model/outlet", e);
        }
    }

    private void fillInOutletState(Map<String, String> statistics, Map<String, String> control, int outletNumber) {
        try {
            Map result = doPost(BASE_URI + "/outlet/" + outletNumber,
                    ImmutableMap.of(
                            "jsonrpc", "2.0",
                            "method", "getState"), Map.class);
            if ((boolean) ((Map) ((Map) result.get("result")).get("_ret_")).get("available")) {
                statistics.put(getOutletDisplayName(outletNumber),
                        String.valueOf((int) ((Map) ((Map) result.get("result")).get("_ret_")).get("powerState") == 1));
                control.put(getOutletDisplayName(outletNumber), "Toggle");
            }
        } catch (Exception e) {
            throw new RuntimeException("Method doesn't not work at the URI " + BASE_URI + "/model/outlet", e);
        }
    }

    private String getOutletDisplayName(int outletNumber) {
        int displayOutletNumber = outletNumber + 1;
        return String.format("%s %d", OUTLET, displayOutletNumber);
    }

    private String getOutletRMSName(int outletNumber) {
        int displayOutletNumber = outletNumber + 1;
        return String.format("%s %d %s", OUTLET, displayOutletNumber, "RMS Current");
    }

    private void fillInStatistics(Map<String, String> statistics, String fieldName, String url, String method) {
        Optional.ofNullable(call(url, method)).ifPresent(response -> statistics.put(fieldName, response));
    }

    private String call(String url, String method) {
        try {
            return ((Map) ((Map) doPost(BASE_URI + url,
                    ImmutableMap.of(
                            "jsonrpc", "2.0",
                            "method", method),
                    Map.class)
                    .get("result"))
                    .get("_ret_"))
                    .get("value").toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> controllableProperties) throws Exception {
        if (CollectionUtils.isEmpty(controllableProperties)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }

        for (ControllableProperty controllableProperty : controllableProperties) {
            controlProperty(controllableProperty);
        }
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) {
        runAsync(() -> {
            try {
                setOutletState(controllableProperty);
            } catch (Exception e) {
                if(this.logger.isErrorEnabled()) {
                    this.logger.error("controlProperty property=" + controllableProperty.getProperty()
                            + "value=" + controllableProperty.getValue() +
                            " deviceId=" + controllableProperty.getDeviceId(), e);
                }
            }
        });
    }

    private void setOutletState(ControllableProperty controllableProperty) throws Exception {
        String response;
        String property = controllableProperty.getProperty();
        int outletNumber = Integer.parseInt(String.valueOf(property.charAt(property.length() - 1)));
        String uri = BASE_URI + "/outlet/" + (outletNumber - 1);
        String data = "{\"jsonrpc\":\"2.0\",\"method\":\"setPowerState\",\"params\":{\"pstate\":" + controllableProperty.getValue() + "}}";
        try {
            Map map = doPost(uri, data, Map.class);
            this.logger.info(map.toString());

            response = ((Map) doPost(uri, data, Map.class)
                    .get("result"))
                    .get("_ret_")
                    .toString();
        } catch (Exception e) {
            throw new Exception("SetPowerState method doesn't not work at the URI " + uri, e);
        }
        if(!this.logger.isInfoEnabled()){
            return;
        }
        int responseCode = Integer.parseInt(response);
        switch (responseCode) {
            case 0:
                this.logger.info("SetPowerState method for Outlet " + outletNumber + " works, response is good");
                break;
            case 1:
                this.logger.info("SetPowerState method for Outlet " + outletNumber + " error: OUTLET NOT SWITCHABLE");
                break;
            case 2:
                this.logger.info("SetPowerState method for Outlet " + outletNumber + " error: LOAD SHEDDING ACTIVE");
                break;
            case 3:
                this.logger.info("SetPowerState method for Outlet " + outletNumber + " error: OUTLET DISABLED");
                break;
            case 4:
                this.logger.info("SetPowerState method for Outlet " + outletNumber + " error: OUTLET NOT OFF");
                break;
            default:
                this.logger.info("Unknown responseCode " + responseCode + " is returned for SetPowerState method for Outlet " + outletNumber);
                break;
        }
    }
}