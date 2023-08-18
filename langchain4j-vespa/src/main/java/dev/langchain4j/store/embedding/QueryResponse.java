package dev.langchain4j.store.embedding;

import java.util.List;

public class QueryResponse {

  private RootNode root;

  public RootNode getRoot() {
    return root;
  }

  public void setRoot(RootNode root) {
    this.root = root;
  }

  public static class RootNode {

    private List<Record> children;

    public List<Record> getChildren() {
      return children;
    }

    public void setChildren(List<Record> children) {
      this.children = children;
    }
  }
}