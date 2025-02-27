package com.okex.open.api.test.ws.publicChannel.config;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.okex.open.api.bean.other.OrderBookItem;
import com.okex.open.api.bean.other.SpotOrderBook;
import com.okex.open.api.bean.other.SpotOrderBookDiff;
import com.okex.open.api.bean.other.SpotOrderBookItem;
import com.okex.open.api.enums.CharsetEnum;
import com.okex.open.api.test.ws.publicChannel.PublicChannelTest;
import com.okex.open.api.utils.DateUtils;
import lombok.Data;
import net.sf.json.JSONObject;
import okhttp3.*;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


public class WebSocketClient {
    private static WebSocket webSocket = null;
    private static Boolean flag = false;
    private static Boolean isConnect = false;
    private static String sign;
    private final static HashFunction crc32 = Hashing.crc32();
    private final static ObjectReader objectReader = new ObjectMapper().readerFor(OrderBookData.class);
    private static Map<String,Optional<SpotOrderBook>> bookMap = new HashMap<>();
    private static Logger logger = Logger.getLogger(PublicChannelTest.class);
    public WebSocketClient() {
    }



    //与服务器建立连接，参数为服务器的URL
    public static WebSocket connection(final String url) {

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            ScheduledExecutorService service;

            @Override
            public void onOpen(final WebSocket webSocket, final Response response) {
                //连接成功后，设置定时器，每隔25s，自动向服务器发送心跳，保持与服务器连接
                isConnect = true;
                System.out.println(Instant.now().toString() + " Connected to the server success!");
                Runnable runnable = new Runnable() {
                    public void run() {
                        // task to run goes here
                        sendMessage("ping");
                    }
                };
                service = Executors.newSingleThreadScheduledExecutor();
                // 第二个参数为首次执行的延时时间，第三个参数为定时执行的间隔时间
                service.scheduleAtFixedRate(runnable, 25, 25, TimeUnit.SECONDS);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                System.out.println("Connection is about to disconnect！");
                webSocket.close(1000, "Long time no message was sent or received！");
                webSocket = null;
            }

            @Override
            public void onClosed(final WebSocket webSocket, final int code, final String reason) {
                System.out.println("Connection dropped！");
            }

            @Override
            public void onFailure(final WebSocket webSocket, final Throwable t, final Response response) {
                System.out.println("Connection failed,Please reconnect!");
                if (Objects.nonNull(service)) {

                    service.shutdown();
                }
            }

            @Override
            public void onMessage(final WebSocket webSocket, final String bytes) {
                //测试服务器返回的字节
                final String byteString=bytes.toString();


                //不进行解压
                final String s = byteString;
//                System.out.println("~~~~~~~~~~~~~~~~~~订阅后推送的数据："+s);
                //判断是否是深度接口
                if (s.contains("\"channel\":\"books\",")||s.contains("\"channel\":\"books-l2-tbt\",")||s.contains("\"channel\":\"books50-l2-tbt\",")) {
                    //是深度接口
                    if (s.contains("snapshot")) {//记录下第一次的全量数据
                        JSONObject rst = JSONObject.fromObject(s);
                        JSONObject arg = JSONObject.fromObject(rst.get("arg"));
                        net.sf.json.JSONArray dataArr = net.sf.json.JSONArray.fromObject(rst.get("data"));

                        JSONObject data = JSONObject.fromObject(dataArr.get(0));
                        String dataStr = data.toString();
                        Optional<SpotOrderBook> oldBook = parse(dataStr);
                        String instrumentId = arg.get("instId").toString();
                        bookMap.put(instrumentId,oldBook);
                    } else if (s.contains("\"action\":\"update\",")) {//是后续的增量，则需要进行深度合并

                        JSONObject rst = JSONObject.fromObject(s);
                        JSONObject arg =JSONObject.fromObject(rst.get("arg"));
                        net.sf.json.JSONArray dataArr = net.sf.json.JSONArray.fromObject(rst.get("data"));
                        JSONObject data = JSONObject.fromObject(dataArr.get(0));
                        String dataStr = data.toString();

                        String instrumentId = arg.get("instId").toString();

                        Optional<SpotOrderBook> oldBook = bookMap.get(instrumentId);
                        Optional<SpotOrderBook> newBook = parse(dataStr);

                        //获取增量的ask
                        List<SpotOrderBookItem> askList = newBook.get().getAsks();
                        //获取增量的bid
                        List<SpotOrderBookItem> bidList = newBook.get().getBids();

                        SpotOrderBookDiff bookdiff = oldBook.get().diff(newBook.get());



                        System.out.println("名称："+instrumentId+",深度合并成功！checknum值为：" + bookdiff.getChecksum() + ",合并后的数据为：" + bookdiff.toString());

                        String str = getStr(bookdiff.getAsks(), bookdiff.getBids());
                        System.out.println("名称："+instrumentId+",拆分要校验的字符串：" + str);
                        //计算checksum值
                        int checksum = checksum(bookdiff.getAsks(), bookdiff.getBids());
                        System.out.println("名称："+instrumentId+",校验的checksum:" + checksum);
                        boolean flag = checksum==bookdiff.getChecksum()?true:false;
                        if(flag){
                            System.out.println("名称："+instrumentId+",深度校验结果为："+flag);
                            oldBook = parse(bookdiff.toString());
                            bookMap.put(instrumentId,oldBook);
                        }else{
                            System.out.println("名称："+instrumentId+",深度校验结果为："+flag+"数据有误！请重连！");
                            //获取订阅的频道和币对
                            String channel = rst.get("table").toString();
                            String unSubStr = "{\"op\": \"unsubscribe\", \"args\":[\"" + channel+":"+instrumentId + "\"]}";
                            System.out.println(DateFormatUtils.format(new Date(), DateUtils.TIME_STYLE_S4) + " Send: " + unSubStr);
                            webSocket.send(unSubStr);
                            String subStr = "{\"op\": \"subscribe\", \"args\":[\"" + channel+":"+instrumentId + "\"]}";
                            System.out.println(DateFormatUtils.format(new Date(), DateUtils.TIME_STYLE_S4) + " Send: " + subStr);
                            webSocket.send(subStr);
                            System.out.println("名称："+instrumentId+",正在重新订阅！");
                        }
                    }
                } else if(s.contains("candle")) {
                    //k线频道
                    System.out.println(DateFormatUtils.format(new Date(), DateUtils.TIME_STYLE_S4) + " Receive: " + s);

                } else if(s.contains("pong")){
                    System.out.println(DateFormatUtils.format(new Date(), DateUtils.TIME_STYLE_S4) + " Receive: " + s);

                }else {
                    //不是深度 k线接口
                    JSONObject rst = JSONObject.fromObject(s);
                    net.sf.json.JSONArray dataArr = net.sf.json.JSONArray.fromObject(rst.get("data"));
                    JSONObject data = JSONObject.fromObject(dataArr.get(0));

                    Long pushTimestamp=null;
                    Long localTimestamp = DateTime.now().getMillis();
                    Long timing = null;


                    if(dataArr.toString().contains("\"ts\"")){
                        pushTimestamp= Long.parseLong(data.get("ts").toString());

                        timing =localTimestamp-pushTimestamp;

                        System.out.println(DateFormatUtils.format(new Date(), DateUtils.TIME_STYLE_S4) +"("+timing+"ms)" + " Receive: " + s);


                    }else {
                        System.out.println(DateFormatUtils.format(new Date(), DateUtils.TIME_STYLE_S4) + " Receive: " + s);
                    }


                }
                if (null != s && s.contains("login")) {
                    if (s.endsWith("true}")) {
                        flag = true;
                    }
                }
            }
        });
        return webSocket;
    }


    private static void isLogin(String s) {
        if (null != s && s.contains("login")) {
            if (s.endsWith("true}")) {
                flag = true;
            }
        }
    }

    //获得sign
    private static String sha256_HMAC(String message, String secret) {
        String hash = "";
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(CharsetEnum.UTF_8.charset()), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            byte[] bytes = sha256_HMAC.doFinal(message.getBytes(CharsetEnum.UTF_8.charset()));
            hash = Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            System.out.println("Error HmacSHA256 ===========" + e.getMessage());
        }
        return hash;
    }

    private static String listToJson(List<Map> list) {
        JSONArray jsonArray = new JSONArray();
        for (Map map : list) {
            jsonArray.add(JSONObject.fromObject(map));
        }
        return jsonArray.toJSONString();
    }

    //登录
    public static void login(String apiKey, String passPhrase, String secretKey) {
        String timestamp = (Double.parseDouble(DateUtils.getEpochTime()) + 28800) + "";
        String message = timestamp + "GET" + "/users/self/verify";
        sign = sha256_HMAC(message, secretKey);
        String str = "{\"op\"" + ":" + "\"login\"" + "," + "\"args\"" + ":" + "[" + "\"" + apiKey + "\"" + "," + "\"" + passPhrase + "\"" + "," + "\"" + timestamp + "\"" + "," + "\"" + sign + "\"" + "]}";
        sendMessage(str);
    }


    //订阅，参数为频道组成的集合
    public static void subscribe(List<Map> list) {
        String s = listToJson(list);
        String str = "{\"op\": \"subscribe\", \"args\":" + s + "}";
        if (null != webSocket)
            sendMessage(str);
    }

    //取消订阅，参数为频道组成的集合
    public static void unsubscribe(List<Map> list) {
        String s = listToJson(list);
        String str = "{\"op\": \"unsubscribe\", \"args\":" + s + "}";
        if (null != webSocket)
            sendMessage(str);
    }

    private static void sendMessage(String str) {
        if (null != webSocket) {
            try {
                Thread.sleep(1300);
            } catch (Exception e) {
                e.printStackTrace();
            }

            System.out.println(DateFormatUtils.format(new Date(), DateUtils.TIME_STYLE_S4)+"Send a message to the server:" + str);
            webSocket.send(str);
        } else {
            System.out.println("Please establish the connection before you operate it！");
        }
    }

    //断开连接
    public static void closeConnection() {
        if (null != webSocket) {
            webSocket.close(1000, "User actively closes the connection");
        } else {
            System.out.println("Please establish the connection before you operate it！");
        }
    }

    public boolean getIsLogin() {
        return flag;
    }

    public boolean getIsConnect() {
        return isConnect;
    }

    public static <T extends OrderBookItem> int checksum(List<T> asks, List<T> bids) {
        System.out.println("深度");
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            if (i < bids.size()) {
                s.append(bids.get(i).getPrice().toString());
                s.append(":");
                s.append(bids.get(i).getSize());
                s.append(":");
            }
            if (i < asks.size()) {
                s.append(asks.get(i).getPrice().toString());
                s.append(":");
                s.append(asks.get(i).getSize());
                s.append(":");
            }
        }
        final String str;
        if (s.length() > 0) {
            str = s.substring(0, s.length() - 1);
        } else {
            str = "";
        }

        return crc32.hashString(str, StandardCharsets.UTF_8).asInt();
    }

    private static <T extends OrderBookItem> String getStr(List<T> asks, List<T> bids) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            if (i < bids.size()) {
                s.append(bids.get(i).getPrice().toString());
                s.append(":");
                s.append(bids.get(i).getSize());
                s.append(":");
            }
            if (i < asks.size()) {
                s.append(asks.get(i).getPrice().toString());
                s.append(":");
                s.append(asks.get(i).getSize());
                s.append(":");
            }
        }
        final String str;
        if (s.length() > 0) {
            str = s.substring(0, s.length() - 1);
        } else {
            str = "";
        }
        return str;
    }

    public static Optional<SpotOrderBook> parse(String json) {

        try {
            OrderBookData data = objectReader.readValue(json);
            List<SpotOrderBookItem> asks =
                    data.getAsks().stream().map(x -> new SpotOrderBookItem(new String(x.get(0)), x.get(1), x.get(2), x.get(3)))
                            .collect(Collectors.toList());

            List<SpotOrderBookItem> bids =
                    data.getBids().stream().map(x -> new SpotOrderBookItem(new String(x.get(0)), x.get(1), x.get(2), x.get(3)))
                            .collect(Collectors.toList());

            return Optional.of(new SpotOrderBook(asks, bids, data.getTs(),data.getChecksum()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Data
    public static class OrderBookData {
        private List<List<String>> asks;
        private List<List<String>> bids;
        private String ts;
        private int checksum;

        public List<List<String>> getAsks() {
            return asks;
        }

        public void setAsks(List<List<String>> asks) {
            this.asks = asks;
        }

        public List<List<String>> getBids() {
            return bids;
        }

        public void setBids(List<List<String>> bids) {
            this.bids = bids;
        }

        public String getTs() {
            return ts;
        }

        public void setTs(String ts) {
            this.ts = ts;
        }

        public int getChecksum() {
            return checksum;
        }

        public void setChecksum(int checksum) {
            this.checksum = checksum;
        }
    }
}
