package dev.langchain4j.store.embedding;

import static dev.langchain4j.internal.Utils.generateUUIDFrom;
import static dev.langchain4j.internal.Utils.randomUUID;

import ai.vespa.client.dsl.A;
import ai.vespa.client.dsl.Q;
import ai.vespa.feed.client.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.internal.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Builder;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.net.URIBuilder;

public class VespaEmbeddingStoreImpl implements EmbeddingStore<TextSegment> {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
  private static final String DEFAULT_NAMESPACE = "namespace";
  // TODO
  private static final String DEFAULT_DOCUMENT_TYPE = "langchain4j";
  private static final boolean DEFAULT_AVOID_DUPS = true;
  // TODO
  private static final String FIELD_NAME_TEXT_SEGMENT = "text_segment";
  private static final String FIELD_NAME_VECTOR = "vector";
  public static final String FIELD_NAME_DOCUMENT_ID = "documentid";

  private final String url;
  private final String keyPath;
  private final String certPath;
  private final Duration timeout;
  private final String namespace;
  private final String documentType;
  private final boolean avoidDups;

  @Builder
  public VespaEmbeddingStoreImpl(
    String url,
    String keyPath,
    String certPath,
    Duration timeout,
    String namespace,
    String documentType,
    Boolean avoidDups
  ) {
    this.url = url;
    this.keyPath = keyPath;
    this.certPath = certPath;
    this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
    this.namespace = namespace != null ? namespace : DEFAULT_NAMESPACE;
    this.documentType = documentType != null ? documentType : DEFAULT_DOCUMENT_TYPE;
    this.avoidDups = avoidDups != null ? avoidDups : DEFAULT_AVOID_DUPS;
  }

  @Override
  public String add(Embedding embedding) {
    return null;
  }

  @Override
  public void add(String id, Embedding embedding) {}

  @Override
  public String add(Embedding embedding, TextSegment textSegment) {
    return null;
  }

  @Override
  public List<String> addAll(List<Embedding> embeddings) {
    return null;
  }

  @Override
  public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
    if (embedded != null && embeddings.size() != embedded.size()) {
      throw new IllegalArgumentException("The list of embeddings and embedded must have the same size");
    }

    List<String> ids = new ArrayList<>();

    try (JsonFeeder jsonFeeder = buildJsonFeeder()) {
      List<Record> records = new ArrayList<>();

      for (int i = 0; i < embeddings.size(); i++) {
        String recordId = avoidDups && embedded != null ? generateUUIDFrom(embedded.get(i).text()) : randomUUID();
        DocumentId documentId = DocumentId.of(namespace, documentType, recordId);
        String text = embedded != null ? embedded.get(i).text() : null;
        //        String json = Json.toJson(new Record(documentId.toString(), embedded.get(i).text(), embeddings.get(i).vectorAsList()));
        records.add(new Record(documentId.toString(), text, embeddings.get(i).vectorAsList()));
      }

      jsonFeeder.feedMany(
        Json.toInputStream(records, List.class),
        new JsonFeeder.ResultCallback() {
          @Override
          public void onNextResult(Result result, FeedException error) {
            if (error == null) {
              if (Result.Type.success.equals(result.type())) {
                ids.add(result.documentId().toString());
              }
            } else {
              throw new RuntimeException(error.getMessage());
            }
          }

          @Override
          public void onError(FeedException error) {
            throw new RuntimeException(error.getMessage());
          }
        }
      );
      //        CompletableFuture<Result> promise = jsonFeeder.feedSingle(json);
      //        promise.whenComplete(
      //          (
      //            (result, throwable) -> {
      //              if (throwable != null) {
      //                throw new RuntimeException(throwable);
      //              } else {
      //                System.out.printf(
      //                  "'%s' for document '%s': %s%n",
      //                  result.type(),
      //                  result.documentId(),
      //                  result.resultMessage()
      //                );
      //                ids.add(result.documentId().toString());
      //              }
      //            }
      //          )
      //        );

    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return ids;
  }

  @Override
  public List<EmbeddingMatch<TextSegment>> findRelevant(Embedding referenceEmbedding, int maxResults) {
    CloseableHttpResponse response;
    try (CloseableHttpClient httpClient = buildQueryClient()) {
      String searchQuery = Q
        .select(FIELD_NAME_DOCUMENT_ID, FIELD_NAME_TEXT_SEGMENT, FIELD_NAME_VECTOR)
        .from(documentType)
        .where(Q.nearestNeighbor(FIELD_NAME_VECTOR, "q").annotate(A.a("targetHits", 10)))
        .fix()
        .hits(maxResults)
        .ranking("semantic_similarity")
        .param("input.query(q)", Json.toJson(referenceEmbedding.vectorAsList()))
        .build();

      URI queryUri = new URIBuilder(url).setPath("search/").setCustomQuery(searchQuery).build();

      // TODO try with resources?
      //      response = httpClient.execute(new HttpGet(queryUri));
      QueryResponse parsedResponse = Json.fromJson(
        Request.get(queryUri).execute(httpClient).returnContent().asString(),
        QueryResponse.class
      );

      return parsedResponse
        .getRoot()
        .getChildren()
        .stream()
        .map(VespaEmbeddingStoreImpl::mapResponseItem)
        .collect(Collectors.toList());
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  public List<EmbeddingMatch<TextSegment>> findRelevant(
    Embedding referenceEmbedding,
    int maxResults,
    double minSimilarity
  ) {
    return null;
  }

  private JsonFeeder buildJsonFeeder() {
    return JsonFeeder
      .builder(
        FeedClientBuilder.create(URI.create(url)).setCertificate(Paths.get(certPath), Paths.get(keyPath)).build()
      )
      .withTimeout(timeout)
      .build();
  }

  private CloseableHttpClient buildQueryClient() throws IOException {
    return HttpClients
      .custom()
      .setConnectionManager(
        PoolingHttpClientConnectionManagerBuilder
          .create()
          .setSSLSocketFactory(
            SSLConnectionSocketFactoryBuilder
              .create()
              .setSslContext(
                new VespaSslContextBuilder().withCertificateAndKey(Paths.get(certPath), Paths.get(keyPath)).build()
              )
              .build()
          )
          .build()
      )
      .build();
  }

  private static EmbeddingMatch<TextSegment> mapResponseItem(Record in) {
    return new EmbeddingMatch(
      in.getRelevance(),
      in.getFields().getDocumentId(),
      Embedding.from(in.getFields().getVector().getValues()),
      TextSegment.from(in.getFields().getTextSegment())
    );
  }
}
