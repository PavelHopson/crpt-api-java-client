import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Класс для работы с API Честного знака.
 * Обеспечивает thread-safe взаимодействие с API с ограничением на количество запросов.
 */
public class CrptApi {
    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final long timeInMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("CrptApi-Scheduler");
        return t;
    });
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String apiUrl = "https://ismp.crpt.ru/api/v3";
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition requestsAvailable = lock.newCondition();
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final long maxWaitTime;
    private String authToken;
    private volatile boolean isShuttingDown = false;

    /**
     * Конструктор класса
     * 
     * @param timeUnit     Временной интервал (секунда, минута и т.д.)
     * @param requestLimit Максимальное количество запросов за указанный интервал
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        validateParameters(timeUnit, requestLimit);
        
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.timeInMillis = timeUnit.toMillis(1);
        this.maxWaitTime = timeInMillis * 2; // Даем двойной запас времени для ожидания
        
        // Создаем HTTP клиент с разумными таймаутами
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        
        // Запускаем таймер для сброса счетчика запросов
        startRequestCounterScheduler();
    }

    /**
     * Валидация входных параметров.
     */
    private void validateParameters(TimeUnit timeUnit, int requestLimit) {
        if (timeUnit == null) {
            throw new IllegalArgumentException("TimeUnit не может быть null. Это как пытаться бежать без ног.");
        }
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit должен быть положительным числом. Ноль запросов - это грустно.");
        }
    }

    /**
     * Запускает планировщик для сброса счетчика запросов.
     */
    private void startRequestCounterScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            lock.lock();
            try {
                // Сбрасываем счетчик запросов
                int previousCount = requestCounter.getAndSet(0);
                
                // Если были заблокированные потоки, будим их
                if (previousCount >= requestLimit) {
                    requestsAvailable.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }, timeInMillis, timeInMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Создаёт документ для ввода в оборот товара, произведенного в РФ.
     * Это как сказать миру: "Смотрите, у нас есть новый классный товар!"
     * 
     * @param document  Документ в виде Java объекта (структура как в PDF)
     * @param signature Подпись документа в виде строки (как ваш автограф на фото)
     * @throws IOException Если что-то пошло не так при работе с сетью
     */
    public void createDocument(Object document, String signature) throws IOException {
        // Проверяем, не выключаемся ли мы
        if (isShuttingDown) {
            throw new IllegalStateException("API выключается. Не принимаем новые запросы. Как магазин после 22:00.");
        }
        
        // Ждем, пока не появится возможность сделать запрос
        waitForRequestOpportunity();
        
        // Получаем или обновляем токен аутентификации
        ensureValidAuthToken();
        
        // Формируем и отправляем запрос
        executeDocumentCreationRequest(document, signature);
        
        // Увеличиваем счетчик запросов
        requestCounter.incrementAndGet();
    }

    /**
     * Ждет, пока не появится возможность сделать запрос. Как очередь в магазине.
     */
    private void waitForRequestOpportunity() {
        lock.lock();
        try {
            long remainingWaitTime = maxWaitTime;
            long startTime = System.currentTimeMillis();
            
            while (requestCounter.get() >= requestLimit) {
                if (remainingWaitTime <= 0) {
                    throw new RuntimeException("Превышено время ожидания для выполнения запроса. " +
                            "API перегружено, как сервер в День сурка.");
                }
                
                // Ждем, пока не освободится слот
                if (!requestsAvailable.await(remainingWaitTime, TimeUnit.MILLISECONDS)) {
                    // Если время ожидания истекло, выходим
                    break;
                }
                
                // Пересчитываем оставшееся время ожидания
                remainingWaitTime = maxWaitTime - (System.currentTimeMillis() - startTime);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Поток был прерван во время ожидания. Как телефонный звонок во время обеда.", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Убеждаемся, что токен аутентификации действителен.
     */
    private void ensureValidAuthToken() throws IOException {
        if (authToken == null) {
            authToken = getFreshAuthToken();
        }
    }

    /**
     * Получает новый аутентификационный токен.
     */
    private String getFreshAuthToken() throws IOException {
        // Шаг 1: Получаем данные для аутентификации
        AuthKeyResponse authKeyResponse = fetchAuthKey();
        
        // Шаг 2: Подписываем данные
        String signedData = signData(authKeyResponse.data);
        
        // Шаг 3: Получаем токен
        return fetchAuthToken(authKeyResponse.uuid, signedData);
    }

    /**
     * Получает данные для аутентификации от API.
     */
    private AuthKeyResponse fetchAuthKey() throws IOException {
        Request request = new Request.Builder()
                .url(apiUrl + "/auth/cert/key")
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Не удалось получить ключ аутентификации. " +
                        "Статус: " + response.code() + ". Как попытка открыть дверь без ключа.");
            }
            
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Пустой ответ от сервера. Как письмо без текста.");
            }
            
            return objectMapper.readValue(body.string(), AuthKeyResponse.class);
        }
    }

    /**
     * Получает аутентификационный токен.
     */
    private String fetchAuthToken(String uuid, String signedData) throws IOException {
        RequestBody formBody = new FormBody.Builder()
                .add("uuid", uuid)
                .add("data", signedData)
                .build();
        
        Request request = new Request.Builder()
                .url(apiUrl + "/auth/cert/")
                .post(formBody)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Не удалось получить аутентификационный токен. " +
                        "Статус: " + response.code() + ". Как попытка войти без пароля.");
            }
            
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Пустой ответ от сервера. Как обещание без содержания.");
            }
            
            AuthTokenResponse authTokenResponse = objectMapper.readValue(body.string(), AuthTokenResponse.class);
            return authTokenResponse.token;
        }
    }

    /**
     * Подписывает данные с использованием сертификата. 
     * В реальной жизни здесь должна быть криптография, но мы делаем вид, что всё серьезно.
     */
    private String signData(String data) {
        // В реальной системе здесь должна быть реализация подписи данных с использованием сертификата
        // Для примера просто возвращаем данные в Base64, как будто мы их подписали
        return Base64.getEncoder().encodeToString(data.getBytes());
    }

    /**
     * Формирует и отправляет запрос на создание документа.
     */
    private void executeDocumentCreationRequest(Object document, String signature) throws IOException {
        // Подготавливаем тело запроса
        RequestBody requestBody = createMultipartRequestBody(document, signature);
        
        // Формируем запрос
        Request request = new Request.Builder()
                .url(apiUrl + "/lk/documents/create?pg=shoes")
                .addHeader("Authorization", "Bearer " + authToken)
                .post(requestBody)
                .build();
        
        // Выполняем запрос
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                // Проверяем, не нужно ли обновить токен
                if (response.code() == 401) {
                    authToken = getFreshAuthToken();
                    request = request.newBuilder()
                            .header("Authorization", "Bearer " + authToken)
                            .build();
                    
                    try (Response retryResponse = httpClient.newCall(request).execute()) {
                        if (!retryResponse.isSuccessful()) {
                            throw new IOException("Не удалось создать документ после повторной попытки. " +
                                    "Статус: " + retryResponse.code() + ". Как второй шанс, который не сработал.");
                        }
                    }
                } else {
                    throw new IOException("Не удалось создать документ. Статус: " + response.code() +
                            ". Ошибка: " + (response.body() != null ? response.body().string() : "без деталей"));
                }
            }
        }
    }

    /**
     * Создает multipart тело запроса для отправки документа.
     */
    private RequestBody createMultipartRequestBody(Object document, String signature) throws IOException {
        try {
            String jsonDocument = objectMapper.writeValueAsString(document);
            
            return new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("product_document", jsonDocument)
                    .addFormDataPart("signature", signature)
                    .addFormDataPart("type", "LP_INTRODUCE_GOODS")
                    .addFormDataPart("document_format", "MANUAL")
                    .build();
        } catch (Exception e) {
            throw new IOException("Ошибка сериализации документа. Как попытка упаковать воду в коробку.", e);
        }
    }

    /**
     * Закрывает все ресурсы.
     */
    public void shutdown() {
        isShuttingDown = true;
        
        // Останавливаем планировщик
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            shutdown();
        } finally {
            super.finalize();
        }
    }

    /**
     * Внутренний класс для десериализации ответа с UUID и данными.
     */
    private static class AuthKeyResponse {
        public String uuid;
        public String data;
    }

    /**
     * Внутренний класс для десериализации ответа с токеном.
     */
    private static class AuthTokenResponse {
        public String token;
    }

    /**
     * Внутренний класс для документа ввода в оборот.
     * Структура как в официальной документации API.
     */
    public static class DocumentDescription {
        private String participantInn;
        private String productionDate;
        private String productionType;
        private String importRequest;
        private String ownerInn;
        private String producerInn;
        private List<Product> products;
        private String regDate;
        private String regNumber;
        
        // Конструктор для удобства создания объекта
        public DocumentDescription(String participantInn, String productionDate, List<Product> products) {
            this.participantInn = participantInn;
            this.productionDate = productionDate;
            this.products = products;
            this.productionType = "OWN_PRODUCTION"; // По умолчанию собственное производство
        }
        
        // Getters и setters
        public String getParticipantInn() { return participantInn; }
        public void setParticipantInn(String participantInn) { this.participantInn = participantInn; }
        public String getProductionDate() { return productionDate; }
        public void setProductionDate(String productionDate) { this.productionDate = productionDate; }
        public String getProductionType() { return productionType; }
        public void setProductionType(String productionType) { this.productionType = productionType; }
        public String getImportRequest() { return importRequest; }
        public void setImportRequest(String importRequest) { this.importRequest = importRequest; }
        public String getOwnerInn() { return ownerInn; }
        public void setOwnerInn(String ownerInn) { this.ownerInn = ownerInn; }
        public String getProducerInn() { return producerInn; }
        public void setProducerInn(String producerInn) { this.producerInn = producerInn; }
        public List<Product> getProducts() { return products; }
        public void setProducts(List<Product> products) { this.products = products; }
        public String getRegDate() { return regDate; }
        public void setRegDate(String regDate) { this.regDate = regDate; }
        public String getRegNumber() { return regNumber; }
        public void setRegNumber(String regNumber) { this.regNumber = regNumber; }
        
        /**
         * Внутренний класс для информации о продукте.
         */
        public static class Product {
            private String certificateDocument;
            private String certificateDocumentDate;
            private String certificateDocumentNumber;
            private String ownerInn;
            private String producerInn;
            private String productionDate;
            private String tnvedCode;
            private String uitCode;
            private String uituCode;
            
            // Конструктор для удобства
            public Product(String tnvedCode, String uitCode) {
                this.tnvedCode = tnvedCode;
                this.uitCode = uitCode;
            }
            
            // Getters и setters
            public String getCertificateDocument() { return certificateDocument; }
            public void setCertificateDocument(String certificateDocument) { this.certificateDocument = certificateDocument; }
            public String getCertificateDocumentDate() { return certificateDocumentDate; }
            public void setCertificateDocumentDate(String certificateDocumentDate) { this.certificateDocumentDate = certificateDocumentDate; }
            public String getCertificateDocumentNumber() { return certificateDocumentNumber; }
            public void setCertificateDocumentNumber(String certificateDocumentNumber) { this.certificateDocumentNumber = certificateDocumentNumber; }
            public String getOwnerInn() { return ownerInn; }
            public void setOwnerInn(String ownerInn) { this.ownerInn = ownerInn; }
            public String getProducerInn() { return producerInn; }
            public void setProducerInn(String producerInn) { this.producerInn = producerInn; }
            public String getProductionDate() { return productionDate; }
            public void setProductionDate(String productionDate) { this.productionDate = productionDate; }
            public String getTnvedCode() { return tnvedCode; }
            public void setTnvedCode(String tnvedCode) { this.tnvedCode = tnvedCode; }
            public String getUitCode() { return uitCode; }
            public void setUitCode(String uitCode) { this.uitCode = uitCode; }
            public String getUituCode() { return uituCode; }
            public void setUituCode(String uituCode) { this.uituCode = uituCode; }
        }
    }
    
    /**
     * Пример использования класса.
     */
    public static void main(String[] args) {
        try {
            // Создаем экземпляр API с ограничением 5 запросов в минуту
            CrptApi api = new CrptApi(TimeUnit.MINUTES, 5);
            
            // Создаем документ для ввода в оборот
            List<DocumentDescription.Product> products = new ArrayList<>();
            products.add(new DocumentDescription.Product("1111111111", "01234567890123456789"));
            
            DocumentDescription document = new DocumentDescription("1234567890", "2023-01-01", products);
            document.setOwnerInn("1234567890");
            document.setProducerInn("1234567890");
            
            // Отправляем документ (подпись здесь условная)
            api.createDocument(document, "VGhpcyBpcyBhIHNpZ25hdHVyZQ==");
            
            System.out.println("Документ успешно отправлен! Как письмо, которое долетело до адресата.");
        } catch (Exception e) {
            System.err.println("Произошла ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }
}