package be.wegenenverkeer.clientcode;

import be.wegenenverkeer.rest.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import rx.Observable;
import rx.observers.TestSubscriber;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.Assert.*;

/**
 * Behavior Unit test
 * Created by Karel Maesen, Geovise BVBA on 06/12/14.
 */
public class RestClientDesignTests {


    static final int REQUEST_TIME_OUT = 100;
    static final int DEFAULT_TIME_OUT = REQUEST_TIME_OUT * 5;

    static int port = 8089;

    static WireMockServer server;

    //use one Client for all tests.
    static RestClient client;


    @BeforeClass
    public static void setUpAndStartServer() {
        server = new WireMockServer(wireMockConfig().port(port));
        server.start();
        configureFor("localhost", port);

        client = new RestClient.Builder()
                .setRequestTimeout(REQUEST_TIME_OUT)
                .setMaxConnections(10)
                .setAccept("application/json")
                .setBaseUrl("http://localhost:" + port)
                .build();

    }

    @AfterClass
    public static void shutDownServer() {
        server.shutdown();
    }

    @Before
    public void resetServer() {
        WireMock.resetToDefault();
    }

    public List<String> items(String... v) {
        return Arrays.asList(v);
    }

    @Test
    public void GETHappyPath() throws InterruptedException {
        //set up stub
        String expectBody = "{ 'contacts': [1,2,3] }";
        stubFor(get(urlPathEqualTo("/contacts?q=test"))
                .withQueryParam("q", equalTo("test"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(aResponse().withFixedDelay(REQUEST_TIME_OUT/3)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(expectBody)));

        //set up use case
        String path = "/contacts";
        ClientRequest request = client.requestBuilder()
                .setMethod("GET")
                .setUrlRelativetoBase(path)
                .addQueryParam("q", "test")
                .build();

        Observable<String> observable = client.sendRequest(request, ServerResponse::getResponseBody);


        TestSubscriber<String> sub = new TestSubscriber<>();
        observable.subscribe(sub);

        sub.awaitTerminalEvent(DEFAULT_TIME_OUT, TimeUnit.MILLISECONDS);
        sub.assertNoErrors();

        sub.assertReceivedOnNext(items(expectBody));


    }

    @Test
    public void demonstrateComposableObservable() throws InterruptedException {
        //set up stubs
        String expectBody = "{ 'contacts': ['contacts/1','contacts/2','contacts/3'] }";
        stubFor(get(urlPathEqualTo("/contacts?q=test"))
                .withQueryParam("q", equalTo("test"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(aResponse().withFixedDelay(10)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(expectBody)));
        stubFor(get(urlPathEqualTo("/contacts/1")).withHeader("Accept", equalTo("application/json")).willReturn(aResponse().withStatus(404).withBody("ONE")));
        stubFor(get(urlPathEqualTo("/contacts/2")).withHeader("Accept", equalTo("application/json")).willReturn(aResponse().withStatus(200).withBody("TWO")));
        stubFor(get(urlPathEqualTo("/contacts/3")).withHeader("Accept", equalTo("application/json")).willReturn(aResponse().withStatus(200).withBody("THREE")));



        //use case
        String path = "/contacts";
        ClientRequest request = client.requestBuilder()
                .setMethod("GET")
                .setUrlRelativetoBase(path)
                .addQueryParam("q", "test")
                .build();

        Function<String, Observable<String>> followLink  = (String contactUrl) -> {
            ClientRequest followUp = client.requestBuilder()
                    .setMethod("GET")
                    .setUrlRelativetoBase(contactUrl).build();
            return client
                    .sendRequest(followUp, ServerResponse::getResponseBody)
                    .onErrorResumeNext(Observable.just("ERROR"));
        };

        Observable<String> observable = client.sendRequest(request, ServerResponse::getResponseBody)
                .flatMap(body -> {
                    List<String> l = JsonPath.read(body, "$.contacts");
                    return Observable.from(l);
                }).flatMap(contactUrl -> followLink.apply(contactUrl));


        //verify behaviour
        TestSubscriber<String> sub = new TestSubscriber<>();
        observable.subscribe(sub);
        sub.awaitTerminalEvent(DEFAULT_TIME_OUT, TimeUnit.MILLISECONDS);

        sub.assertNoErrors();
        assertEquals(new HashSet<String>(items("ERROR", "TWO", "THREE")), new HashSet<String>(sub.getOnNextEvents()));

    }

    @Test
    public void testHttp4xxResponseOnGET() throws InterruptedException {
        //no stub set-up so we always get a 404 response.

        //set up use case
        String path = "/contacts";
        ClientRequest request = client.requestBuilder().setMethod("GET").setUrlRelativetoBase(path).build();
        Observable<String> observable = client.sendRequest(request, ServerResponse::getResponseBody);

        TestSubscriber<String> testsubscriber = new TestSubscriber<>();
        observable.subscribe(testsubscriber);

        testsubscriber.awaitTerminalEvent(DEFAULT_TIME_OUT, TimeUnit.MILLISECONDS);

        List onErrorEvents = testsubscriber.getOnErrorEvents();
        assertFalse(onErrorEvents.isEmpty());
        if (onErrorEvents.get(0) instanceof HttpClientError) {
            HttpClientError hce = (HttpClientError) onErrorEvents.get(0);
            assertEquals(404, hce.getStatusCode());
        } else {
            fail("Didn't receive a HttpClientError");
        }

    }

    @Test
    public void testHttp5xxResponseOnGET() throws InterruptedException {
        //set up stub
        stubFor(get(urlEqualTo("/contacts"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(aResponse().withFixedDelay(20)
                        .withStatus(500)));

        //set up use case
        String path = "/contacts";
        ClientRequest request = client
                .requestBuilder().setMethod("GET")
                .setUrlRelativetoBase(path)
                .build();

        Observable<String> observable = client.sendRequest(request, ServerResponse::getResponseBody);
        TestSubscriber<String> testsubscriber = new TestSubscriber<>();
        observable.subscribe(testsubscriber);

        testsubscriber.awaitTerminalEvent(DEFAULT_TIME_OUT, TimeUnit.MILLISECONDS);

        List onErrorEvents = testsubscriber.getOnErrorEvents();
        assertFalse(onErrorEvents.isEmpty());
        if (onErrorEvents.get(0) instanceof HttpServerError) {
            HttpServerError hce = (HttpServerError) onErrorEvents.get(0);
            assertEquals(500, hce.getStatusCode());
        } else {
            fail("Didn't receive a HttpClientError");
        }

    }


    @Test
    public void testConnectionTimeOut() throws InterruptedException {
        //set up stub
        stubFor(get(urlEqualTo("/contacts"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(aResponse().withFixedDelay(REQUEST_TIME_OUT * 2)
                        .withStatus(200)));

        //set up use case
        String path = "/contacts";
        ClientRequest request = client.requestBuilder().setMethod("GET").setUrlRelativetoBase(path).build();
        Observable<String> observable = client.sendRequest(request, ServerResponse::getResponseBody);

        TestSubscriber<String> testsubscriber = new TestSubscriber<>();
        observable.subscribe(testsubscriber);

        testsubscriber.awaitTerminalEvent(DEFAULT_TIME_OUT, TimeUnit.MILLISECONDS);

        List onErrorEvents = testsubscriber.getOnErrorEvents();
        assertFalse(onErrorEvents.isEmpty());
        assertTrue(onErrorEvents.get(0) instanceof TimeoutException);

    }

    // Note that this test doesn't fail!! Exceptions in errorHandlers are swallowed by rxjava.
    // the Observable is supposed to log any "OnErrorFailedExceptions at the ERROR-loglevel
//    @Test
//    public void testFaultyOnErrorMethod() throws InterruptedException {
//        CountDownLatch latch = new CountDownLatch(1);
//
//        //set up use case which will return a 404 response
//        String path = "/contacts";
//        ClientRequest request = client.requestBuilder().setMethod("GET").setUrlRelativetoBase(path).build();
//        Observable<String> observable = client.sendRequest(request, ServerResponse::getResponseBody);
//
//        observable.subscribe(
//                s -> {},
//                //force an exception in errorHandler
//                t -> {int a = 100 / 0;},
//                () -> {}
//        );
//
//        latch.await(DEFAULT_TIME_OUT, TimeUnit.MILLISECONDS);
//    }

}