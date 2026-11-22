package com.easymall.refund;

/** NOT_FOUND 仅用于渠道查证后允许按同一请求号继续，
 *  UNKNOWN 保留退款占用。 */
public enum RefundChannelResult {
    SUCCESS, REJECTED, UNKNOWN, NOT_FOUND
}
