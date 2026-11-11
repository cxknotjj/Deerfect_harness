/**
 * 端口载荷：出现在 {@code contract.api} / {@code contract.spi} 签名里的数据类型。
 *
 * <p>只放 record 与枚举。持久化实体、只在 server 内部流转的元数据不放进来——后者的字段
 * 随数据库列和实现细节变化，一旦进入签名就等于把实现细节变成契约（{@code SessionEntity}
 * 曾经如此：改一个列名就会打断渠道与 CLI 的编译）。
 *
 * <p>这里的类型没有值语义（不像 Money / DateRange 可运算可比较），本质是端口的入参与出参。
 * 需要新的返回值时，先问一句：这个类型会不会出现在某个 {@code contract.api} 的方法签名上？
 * 不会，就放回实现模块。
 */
package com.dark.javaHarness.contract.model;
