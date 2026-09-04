package cn.aigc.dit.common.model.req;

import java.io.Serializable;
import java.util.Map;

/**
 * 通用分页请求。
 */
public class PageQueryReq implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private Map<String, String> sortedMap;

    public Integer getPageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Map<String, String> getSortedMap() {
        return sortedMap;
    }

    public void setSortedMap(Map<String, String> sortedMap) {
        this.sortedMap = sortedMap;
    }
}
