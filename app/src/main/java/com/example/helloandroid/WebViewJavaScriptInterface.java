package com.example.helloandroid;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.helloandroid.api.RetrofitAPI;
import com.example.helloandroid.security.HmacUtil;
import com.example.helloandroid.storage.StorageUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import io.appmetrica.analytics.Revenue;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import ru.rustore.sdk.pay.PurchaseInteractor;
import ru.rustore.sdk.pay.RuStorePayClient;
import ru.rustore.sdk.pay.UserInteractor;
import ru.rustore.sdk.pay.model.DeveloperPayload;
import ru.rustore.sdk.pay.model.PreferredPurchaseType;
import ru.rustore.sdk.pay.model.Product;
import ru.rustore.sdk.pay.model.ProductId;
import ru.rustore.sdk.pay.model.ProductPurchaseParams;
import ru.rustore.sdk.pay.model.ProductPurchaseStatus;
import ru.rustore.sdk.pay.model.ProductType;
import ru.rustore.sdk.pay.model.PromotionInfo;
import ru.rustore.sdk.pay.model.Purchase;
import ru.rustore.sdk.pay.model.PurchaseId;
import ru.rustore.sdk.pay.model.RuStorePaymentException;
import ru.rustore.sdk.pay.model.SdkTheme;
import ru.rustore.sdk.review.RuStoreReviewManager;
import ru.rustore.sdk.review.RuStoreReviewManagerFactory;
import ru.rustore.sdk.review.model.ReviewInfo;

import io.appmetrica.analytics.AppMetrica;
/*
 * JavaScript Interface. Web code can access methods in here
 * (as long as they have the @JavascriptInterface annotation)
 */
public class WebViewJavaScriptInterface{
    private static final String PRODUCTS_CALLBACK = "onRustoreProducts";

    private final Activity activity;
    private final WebView webView;

    private final RuStoreReviewManager reviewManager;
    private ReviewInfo reviewInfo = null;

    /*
     * Need a reference to the context in order to sent a post message
     */
    public WebViewJavaScriptInterface(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;

        reviewManager = RuStoreReviewManagerFactory.INSTANCE.create(activity.getApplicationContext());
    }

    @JavascriptInterface
    public String getCreaturesOrder() {
        return StorageUtil.getCreaturesOrder(activity.getApplicationContext());
    }

    @JavascriptInterface
    public void saveCreaturesOrder(String data) {
        StorageUtil.saveCreaturesOrder(activity.getApplicationContext(), data);
    }
    @JavascriptInterface
    public void initPaymentApi(String playerId) {
        State.PLAYER_ID = playerId;

        Timer timer = new Timer();

        lastPurchaseRetry();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                checkPayments();
            }
        }, 0L, 10000L);
    }

    synchronized public void checkPayments() {
        try {
            List<Map<String, String>> failRequests = StorageUtil.getSavedQueries(activity.getApplicationContext());
            for (Map<String, String> f : failRequests) {
                postRequest(Constants.GAME_URL, f.get("productId"), State.PLAYER_ID, f.get("invoiceId"),
                        f.get("purchaseId"));
            }
        } catch (Exception ignore) {

        }
    }

    @JavascriptInterface
    public void initiatePayment(String productId, String playerId) {
        State.PLAYER_ID = playerId;

        String developerPayloadStr = "PlayerId="+playerId+";ProductId="+productId;
        DeveloperPayload developerPayload = new DeveloperPayload(developerPayloadStr);

        ProductPurchaseParams params = new ProductPurchaseParams(new ProductId(productId), null, null, developerPayload, null, null);

        RuStorePayClient ruStorePayClient = RuStorePayClient.Companion.getInstance();
        PurchaseInteractor purchaseInteractor = ruStorePayClient.getPurchaseInteractor();

        purchaseInteractor.purchase(params, PreferredPurchaseType.ONE_STEP, SdkTheme.LIGHT, null)
                .addOnSuccessListener(result -> {
                    try {
                        Map<String, String> params1 = convert(productId, playerId, result.getInvoiceId().getValue(), result.getPurchaseId().getValue());
                        StorageUtil.saveRequest(activity.getApplicationContext(), params1);
                    } catch (Exception ignore) {}

                    postRequest(Constants.GAME_URL, productId, playerId, result.getInvoiceId().getValue(), result.getPurchaseId().getValue());
                    appmetricaEvent(productId, result.getPurchaseId().getValue(), playerId);

                })
                .addOnFailureListener(throwable -> {
                    if (throwable instanceof RuStorePaymentException.ProductPurchaseException) {
                        Toast.makeText(activity, "Ошибка при покупке продукта", Toast.LENGTH_LONG).show();
                    } else if (throwable instanceof RuStorePaymentException.ProductPurchaseCancelled) {
                        Toast.makeText(activity, "Покупка отменена", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(activity, "Ошибка при покупке продукта", Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * Цены товаров из консоли RuStore (с учётом акций). Ответ асинхронный:
     * window.onRustoreProducts(requestId, json), где json — {"products":[...]} или {"error":"..."}.
     * price / basePrice / discountValue — в копейках.
     */
    @JavascriptInterface
    public void getProducts(String requestId, String productIdsJson) {
        List<ProductId> productIds = new ArrayList<>();
        try {
            JSONArray ids = new JSONArray(productIdsJson);
            for (int i = 0; i < ids.length(); i++) {
                productIds.add(new ProductId(ids.getString(i)));
            }
        } catch (JSONException e) {
            sendProductsError(requestId, e);
            return;
        }

        RuStorePayClient.Companion.getInstance().getProductInteractor().getProducts(productIds)
                .addOnSuccessListener(products -> {
                    try {
                        JSONArray result = new JSONArray();
                        for (Product product : products) {
                            result.put(toJson(product));
                        }
                        sendProducts(requestId, new JSONObject().put("products", result));
                    } catch (Exception e) {
                        sendProductsError(requestId, e);
                    }
                })
                .addOnFailureListener(throwable -> sendProductsError(requestId, throwable));
    }

    private static JSONObject toJson(Product product) throws JSONException {
        JSONObject json = new JSONObject()
                .put("productId", product.getProductId().getValue())
                .put("price", product.getPrice() != null ? product.getPrice().getValue() : JSONObject.NULL)
                .put("amountLabel", product.getAmountLabel() != null ? product.getAmountLabel().getValue() : JSONObject.NULL)
                .put("currency", product.getCurrency() != null ? product.getCurrency().getValue() : JSONObject.NULL);

        PromotionInfo promo = product.getPromotionInfo();
        if (promo != null) {
            json.put("promotion", new JSONObject()
                    .put("code", promo.getCode() != null ? promo.getCode().getValue() : JSONObject.NULL)
                    .put("name", promo.getName() != null ? promo.getName() : JSONObject.NULL)
                    .put("basePrice", promo.getBasePrice() != null ? promo.getBasePrice().getValue() : JSONObject.NULL)
                    .put("discountValue", promo.getDiscountValue() != null ? promo.getDiscountValue().getValue() : JSONObject.NULL)
                    .put("discountValueLabel", promo.getDiscountValueLabel() != null ? promo.getDiscountValueLabel().getValue() : JSONObject.NULL)
                    .put("startDate", promo.getStartDate() != null ? promo.getStartDate().getTime() : JSONObject.NULL)
                    .put("endDate", promo.getEndDate() != null ? promo.getEndDate().getTime() : JSONObject.NULL));
        }
        return json;
    }

    private void sendProductsError(String requestId, Throwable throwable) {
        Log.e("getProducts", String.valueOf(throwable));
        try {
            sendProducts(requestId, new JSONObject().put("error", String.valueOf(throwable)));
        } catch (JSONException ignore) {
        }
    }

    private void sendProducts(String requestId, JSONObject payload) {
        String script = "window." + PRODUCTS_CALLBACK + " && window." + PRODUCTS_CALLBACK + "("
                + JSONObject.quote(requestId) + "," + JSONObject.quote(payload.toString()) + ")";
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    @JavascriptInterface
    public boolean isWebView() {
        return true;
    }

    @JavascriptInterface
    public void initRateApp() {
        if (reviewInfo != null) return;

        reviewManager.requestReviewFlow()
                .addOnSuccessListener(reviewInfo -> {
                    Log.i("requestReviewFlow", reviewInfo.toString());
                    this.reviewInfo = reviewInfo;
                    reviewManager.launchReviewFlow(reviewInfo)
                            .addOnSuccessListener(unit -> Log.w("ReviewFlow", "Review Flow started UNIT: " + unit.toString()))
                            .addOnFailureListener(throwable -> Log.e("launchReviewFlow", "Review Flow error" + throwable));
                })
                .addOnFailureListener(throwable -> {
                    Log.w("requestReviewFlow", throwable.toString());
                });
    }

    @JavascriptInterface
    public int getAppVersion() {
        try {
            PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            int versionCode = pInfo.versionCode;
            return versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return 34;
    }

    private void confirmPurchase(String purchaseId, String productId, String playerId, String invoiceId) {
        PurchaseInteractor purchaseInteractor = RuStorePayClient.Companion.getInstance().getPurchaseInteractor();

        purchaseInteractor.confirmTwoStepPurchase(
                new PurchaseId(purchaseId),
                null
        ).addOnSuccessListener( success -> {
            postRequest(Constants.GAME_URL, productId, playerId, invoiceId, purchaseId);

            appmetricaEvent(productId, purchaseId, playerId);
            // Логика успешного подтверждения покупки
        }).addOnFailureListener(throwable -> {
            Toast.makeText(activity, "Ошибка при подтверждении покупки, подождите несколько минут или обратитесь в поддержку", Toast.LENGTH_LONG).show();
            // Обработка ошибки
        });
    }

    private void postRequest(String url, String productId, String playerId, String invoiceId,
                             String purchaseId) {
        String signature = HmacUtil.generateSignature(productId + playerId + invoiceId);

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(url)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        RetrofitAPI retrofitAPI = retrofit.create(RetrofitAPI.class);

        Call<Void> c = retrofitAPI.createPost(signature, productId, playerId, invoiceId);

        c.enqueue(new Callback<Void>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                Map<String, String> params = convert(productId, playerId, invoiceId, purchaseId);
                StorageUtil.removeQuery(activity.getApplicationContext(), params);
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable throwable) {
                logThrowable(throwable, "Ошибка при подтверждении покупки: ");
            }
        });
    }

    private void postRequestLog(String url, String productId, String playerId, String invoiceId) {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(url)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        RetrofitAPI retrofitAPI = retrofit.create(RetrofitAPI.class);

        Call<Void> call = retrofitAPI.createPostLogs(productId, playerId, invoiceId);

        call.enqueue(new Callback<Void>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                Log.i("LOGGING", "Success added log");
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable throwable) {
                logThrowable(throwable, "Ошибка при подтверждении покупки: ");
            }
        });
    }

    private void logThrowable(Throwable throwable, String errorText) {
        if (throwable.getLocalizedMessage() != null) {
            Log.i("ERROR", throwable.getLocalizedMessage());
        }
    }

    private void appmetricaEvent(String productId, String purchaseId, String playerId) {
        boolean isFirstBuy = PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext())
                .getBoolean("first_buy", true);

        String[] parts = productId.split("_");
        String price = parts[parts.length - 1];
        if (isFirstBuy) {
            String firstBuyParameters = "{\"price\":" + price + "}";
            AppMetrica.reportEvent("first_purchase", firstBuyParameters);
            PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext())
                    .edit().putBoolean("first_buy", false).apply();
        }

        Revenue revenue = Revenue.newBuilder(Math.round(Long.parseLong(price) * 1_000_000), Currency.getInstance("RUB"))
                .withProductID(productId)
                .withQuantity(1)
                .withPayload("{\"OrderID\":\""+purchaseId+"\", \"source\":\"Rustore\"}")
                .build();
        AppMetrica.reportRevenue(revenue);

        Map<String, Object> paramsEvent = new HashMap<>();
        paramsEvent.put("playerId", playerId);
        paramsEvent.put("price", price);
        AppMetrica.reportEvent("purchase", paramsEvent);
    }

    private static Map<String, String> convert(String productId, String playerId, String invoiceId, String purchaseId) {
        Map<String, String> params = new HashMap<>();

        params.put("productId", productId);
        params.put("playerId", playerId);
        params.put("invoiceId", invoiceId);
        params.put("purchaseId", purchaseId);
        return params;
    }

    public void lastPurchaseRetry() {
        try {
            PurchaseInteractor purchaseInteractor = RuStorePayClient.Companion.getInstance().getPurchaseInteractor();

            if (purchaseInteractor != null) {
                purchaseInteractor.getPurchases(ProductType.CONSUMABLE_PRODUCT, ProductPurchaseStatus.CONFIRMED, null)
                        .addOnSuccessListener(purchases -> {
                            try {
                                if (purchases != null) {
                                    Purchase lastPurchase = null;
                                    long maxInvoiceId = 0;
                                    for (Purchase purchase : purchases) {
                                        if (purchase != null) {
                                            long invoiceId = Long.parseLong(purchase.getInvoiceId().getValue());
                                            if (invoiceId > maxInvoiceId) {
                                                maxInvoiceId = invoiceId;
                                                lastPurchase = purchase;
                                            }
                                        }
                                    }
                                    Map<String, String> map = new HashMap<>();
                                    if (lastPurchase != null) {
                                        for (String part : Objects.requireNonNull(lastPurchase.getDeveloperPayload()).getValue().split(";")) {
                                            String[] kv = part.split("=");
                                            if (kv.length == 2) {
                                                map.put(kv[0], kv[1]);
                                            }
                                        }

                                        if (!Objects.requireNonNull(map.get("ProductId")).isEmpty() &&
                                                !Objects.requireNonNull(map.get("PlayerId")).isEmpty()) {
                                            String productId = map.get("ProductId");
                                            String playerId = map.get("PlayerId");

                                            Map<String, String> params = convert(productId, playerId,
                                                    lastPurchase.getInvoiceId().getValue(), lastPurchase.getPurchaseId().getValue());
                                            StorageUtil.saveRequest(activity.getApplicationContext(), params);

                                            postRequest(Constants.GAME_URL, productId, playerId,
                                                    lastPurchase.getInvoiceId().getValue(), lastPurchase.getPurchaseId().getValue());
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                postRequestLog(Constants.GAME_URL, "ERROR", State.PLAYER_ID, e.toString());
                                Log.e("lastPurchaseRetry", e.toString());
                            }
                        }).addOnFailureListener(error -> {
                            postRequestLog(Constants.GAME_URL, "ERROR", State.PLAYER_ID, error.toString());
                            Log.e("lastPurchaseRetry", error.toString());
                        });
            }
        } catch (Exception e) {
            postRequestLog(Constants.GAME_URL, "ERROR", State.PLAYER_ID, e.toString());
        }
    }
}
