package com.dark.wms.client;

import com.dark.wms.config.WmsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * WMS REST 客户端单测：信封解包、参数透传、鉴权头、错误分类（鉴权失败/参数错误/服务异常/超时/不可达）。
 * 全部经 MockRestServiceServer 模拟，无真实 WMS 依赖。
 */
class WmsRestClientTest {

    private WmsProperties props;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        props = new WmsProperties();
        props.setApiBase("http://wms.test");
        props.setApiToken("tok-123");
    }

    /** 构造绑定 MockRestServiceServer 的被测客户端（鉴权头由构造器统一追加，与生产行为一致） */
    private WmsRestClient newClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl(props.getApiBase());
        server = MockRestServiceServer.bindTo(builder).build();
        return new WmsRestClient(props, new ObjectMapper(), builder);
    }

    @Test
    void list_解析records与total_带鉴权头_仅GET() {
        WmsRestClient client = newClient();
        server.expect(requestTo(containsString("/inventory/wmsInventory/list")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Access-Token", "tok-123"))
                .andExpect(queryParam("productId", "p1"))
                .andExpect(queryParam("pageNo", "1"))
                .andExpect(queryParam("pageSize", "20"))
                .andRespond(withSuccess("""
                        {"success":true,"code":200,"message":"ok",
                         "result":{"records":[{"id":"i1","stockQuantity":10}],"total":42}}""",
                        APPLICATION_JSON));
        WmsPage page = client.list(WmsEndpoints.INVENTORY_LIST,
                Map.of("productId", "p1", "pageNo", "1", "pageSize", "20"));
        assertThat(page.total()).isEqualTo(42);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0)).containsEntry("stockQuantity", 10);
    }

    @Test
    void list_token未配置则不携带鉴权头_空参数被剔除() {
        props.setApiToken("");
        WmsRestClient client = newClient();
        // 仅 pageNo 非 blank：productName 空值应被剔除
        server.expect(requestTo("http://wms.test/goods/wmsProducts/list?pageNo=1"))
                .andExpect(headerDoesNotExist("X-Access-Token"))
                .andRespond(withSuccess("{\"success\":true,\"code\":200,\"result\":{\"records\":[],\"total\":0}}",
                        APPLICATION_JSON));
        WmsPage page = client.list(WmsEndpoints.PRODUCTS_LIST, Map.of("productName", "", "pageNo", "1"));
        assertThat(page.records()).isEmpty();
    }

    @Test
    void list_业务失败返回中文提示() {
        WmsRestClient client = newClient();
        server.expect(requestTo(containsString("/goods/wmsProducts/list")))
                .andRespond(withSuccess("{\"success\":false,\"code\":500,\"message\":\"系统内部异常\"}", APPLICATION_JSON));
        assertThatThrownBy(() -> client.list(WmsEndpoints.PRODUCTS_LIST, Map.of()))
                .isInstanceOf(WmsApiException.class)
                .hasMessageContaining("业务失败")
                .hasMessageContaining("系统内部异常");
    }

    @Test
    void http401_403_分类为鉴权失败() {
        WmsRestClient client = newClient();
        server.expect(requestTo(containsString("/inventory/wmsInventory/list")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.list(WmsEndpoints.INVENTORY_LIST, Map.of()))
                .isInstanceOf(WmsApiException.class)
                .hasMessageContaining("鉴权失败");
    }

    @Test
    void http400_分类为参数错误() {
        WmsRestClient client = newClient();
        server.expect(requestTo(containsString("/inventory/wmsInventory/list")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> client.list(WmsEndpoints.INVENTORY_LIST, Map.of()))
                .isInstanceOf(WmsApiException.class)
                .hasMessageContaining("参数可能有误");
    }

    @Test
    void http5xx_分类为服务异常() {
        WmsRestClient client = newClient();
        server.expect(requestTo(containsString("/inventory/wmsInventory/list")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> client.list(WmsEndpoints.INVENTORY_LIST, Map.of()))
                .isInstanceOf(WmsApiException.class)
                .hasMessageContaining("服务异常");
    }

    @Test
    void 读取超时_分类为请求超时() {
        WmsRestClient client = newClient();
        server.expect(requestTo(containsString("/inventory/wmsInventory/list")))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThatThrownBy(() -> client.list(WmsEndpoints.INVENTORY_LIST, Map.of()))
                .isInstanceOf(WmsApiException.class)
                .hasMessageContaining("请求超时");
    }

    @Test
    void 连接拒绝_分类为服务不可达() {
        WmsRestClient client = newClient();
        server.expect(requestTo(containsString("/inventory/wmsInventory/list")))
                .andRespond(withException(new ConnectException("Connection refused")));
        assertThatThrownBy(() -> client.list(WmsEndpoints.INVENTORY_LIST, Map.of()))
                .isInstanceOf(WmsApiException.class)
                .hasMessageContaining("不可达");
    }

    @Test
    void 地址未配置_前置拦截不发起请求() {
        WmsRestClient blank = new WmsRestClient(new WmsProperties(), new ObjectMapper());
        assertThatThrownBy(() -> blank.list(WmsEndpoints.INVENTORY_LIST, Map.of()))
                .isInstanceOf(WmsApiException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void one_result对象形态_数组形态取首条兜底() {
        WmsRestClient client = newClient();
        // 两次期望须在任何请求发生前全部声明
        server.expect(requestTo(containsString("/goods/wmsProducts/queryById")))
                .andRespond(withSuccess("{\"success\":true,\"code\":200,\"result\":{\"id\":\"p1\",\"productName\":\"测试商品\"}}",
                        APPLICATION_JSON));
        server.expect(requestTo(containsString("/goods/wmsProducts/queryById")))
                .andRespond(withSuccess("{\"success\":true,\"code\":200,\"result\":[{\"id\":\"p1\"}]}", APPLICATION_JSON));
        assertThat(client.one(WmsEndpoints.PRODUCTS_BY_ID, "p1")).containsEntry("productName", "测试商品");
        assertThat(client.one(WmsEndpoints.PRODUCTS_BY_ID, "p1")).containsEntry("id", "p1");
    }

    @Test
    void children_result数组形态_对象形态包单元素兜底() {
        WmsRestClient client = newClient();
        server.expect(requestTo(containsString("/shipment/wmsShipment/queryWmsShipmentDetailByMainId")))
                .andRespond(withSuccess("{\"success\":true,\"code\":200,\"result\":[{\"id\":\"d1\"},{\"id\":\"d2\"}]}",
                        APPLICATION_JSON));
        server.expect(requestTo(containsString("/shipment/wmsShipment/queryWmsShipmentDetailByMainId")))
                .andRespond(withSuccess("{\"success\":true,\"code\":200,\"result\":{\"id\":\"d1\"}}", APPLICATION_JSON));
        assertThat(client.children(WmsEndpoints.SHIPMENT_DETAILS, "s1")).hasSize(2);
        assertThat(client.children(WmsEndpoints.SHIPMENT_DETAILS, "s1")).hasSize(1);
    }

    @Test
    void result空缺_返回空记录不报错() {
        WmsRestClient client = newClient();
        server.expect(requestTo(containsString("/inventory/wmsInventory/list")))
                .andRespond(withSuccess("{\"success\":true,\"code\":200,\"result\":null}", APPLICATION_JSON));
        WmsPage page = client.list(WmsEndpoints.INVENTORY_LIST, Map.of());
        assertThat(page.records()).isEmpty();
        assertThat(page.total()).isZero();
    }

    @Test
    void 审计字段黑名单统一剔除_业务字段保留_列表详情明细都生效() {
        WmsRestClient client = newClient();
        // 三类出口各声明一次期望：list（records）/ one（queryById）/ children（ByMainId）
        server.expect(requestTo(containsString("/goods/wmsProducts/list")))
                .andRespond(withSuccess("""
                        {"success":true,"code":200,"result":{"records":[{"id":"p1","productName":"红牛",
                        "createBy":"admin","createTime":"2026-01-01 00:00:00","updateBy":"admin",
                        "updateTime":"2026-01-02 00:00:00","sysOrgCode":"A01"}],"total":1}}""",
                        APPLICATION_JSON));
        server.expect(requestTo(containsString("/goods/wmsProducts/queryById")))
                .andRespond(withSuccess(
                        "{\"success\":true,\"code\":200,\"result\":{\"id\":\"p1\",\"productName\":\"红牛\",\"updateBy\":\"admin\"}}",
                        APPLICATION_JSON));
        server.expect(requestTo(containsString("ByMainId")))
                .andRespond(withSuccess(
                        "{\"success\":true,\"code\":200,\"result\":[{\"id\":\"d1\",\"createTime\":\"2026-01-01 00:00:00\"}]}",
                        APPLICATION_JSON));

        // 列表：审计字段剔除，业务字段（id/productName）保留
        WmsPage page = client.list(WmsEndpoints.PRODUCTS_LIST, Map.of());
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0))
                .containsEntry("id", "p1")
                .containsEntry("productName", "红牛")
                .doesNotContainKeys("createBy", "createTime", "updateBy", "updateTime", "sysOrgCode");
        // 详情：同样剔除
        assertThat(client.one(WmsEndpoints.PRODUCTS_BY_ID, "p1"))
                .containsEntry("id", "p1")
                .doesNotContainKeys("updateBy");
        // 子表明细：同样剔除
        assertThat(client.children(WmsEndpoints.SHIPMENT_DETAILS, "s1").get(0))
                .containsEntry("id", "d1")
                .doesNotContainKeys("createTime");
    }
}
