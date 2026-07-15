package be.wegenenverkeer.rxhttpclient;

import be.wegenenverkeer.rxhttpclient.rxjava.RxJavaHttpClient;
import org.junit.Assert;
import org.junit.Test;

/**
 * Created by Karel Maesen, Geovise BVBA on 31/03/16.
 */
public class TestBuilder {


    @Test(expected = IllegalStateException.class)
    public void testBuilderThrowsIllegalArgumentExceptionOnMissingBaseUrl() {
        new RxJavaHttpClient.Builder().build();
    }

    //Netty >= 4.1.135 rejects header values with leading/trailing whitespace, so since
    //async-http-client 2.16.0 such requests fail at construction instead of being sent.
    @Test(expected = IllegalArgumentException.class)
    public void testHeaderValueWithLeadingOrTrailingWhitespaceIsRejected() {
        RxHttpClient client = new RxJavaHttpClient.Builder().setBaseUrl("http://foo.com").build();
        client.requestBuilder().addHeader("p", " phfft ");
    }

    @Test
    public void testRequestSignersAreAdded() {
        RequestSigner requestSigner = clientRequest -> {
        };

        RxHttpClient client = new RxJavaHttpClient.Builder().setBaseUrl("http://foo.com").addRequestSigner(requestSigner).build();
        Assert.assertTrue(client.getRequestSigners().contains(requestSigner));
    }
}
