package cn.aigc.dit.common.model.resp;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 通用分页响应。
 */
public class PageQueryResp<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    private long totalNum;
    private long pageSize;
    private long pageNum;
    private List<T> result = Collections.emptyList();
    private String otherInfo;
    private Long pages;

    public static <T> PageQueryResp<T> of(long pageNum, long pageSize, long totalNum, List<T> result) {
        PageQueryResp<T> resp = new PageQueryResp<>();
        resp.pageNum = pageNum;
        resp.pageSize = pageSize;
        resp.totalNum = totalNum;
        resp.result = result == null ? Collections.emptyList() : result;
        resp.pages = pageSize <= 0 ? 0L : (totalNum + pageSize - 1) / pageSize;
        return resp;
    }

    public long getTotalNum() {
        return totalNum;
    }

    public PageQueryResp<T> setTotalNum(long totalNum) {
        this.totalNum = totalNum;
        return this;
    }

    public long getPageSize() {
        return pageSize;
    }

    public PageQueryResp<T> setPageSize(long pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public long getPageNum() {
        return pageNum;
    }

    public PageQueryResp<T> setPageNum(long pageNum) {
        this.pageNum = pageNum;
        return this;
    }

    public List<T> getResult() {
        return result;
    }

    public PageQueryResp<T> setResult(List<T> result) {
        this.result = result;
        return this;
    }

    public String getOtherInfo() {
        return otherInfo;
    }

    public PageQueryResp<T> setOtherInfo(String otherInfo) {
        this.otherInfo = otherInfo;
        return this;
    }

    public Long getPages() {
        return pages;
    }

    public PageQueryResp<T> setPages(Long pages) {
        this.pages = pages;
        return this;
    }
}
