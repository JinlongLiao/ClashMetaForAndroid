package com.github.kr328.clash.design.model

/**
 * Public network identity returned by the IP information provider.
 *
 * @property ip public IPv4 or IPv6 address.
 * @property location human-readable location summary.
 * @property organization network operator or autonomous-system organization.
 */
data class PublicNetworkInfo(
    val ip: String,
    val location: String,
    val organization: String,
)

/**
 * Result of opening one network probe URL.
 *
 * @property name stable target name.
 * @property latencyMillis elapsed time through receipt of the HTTP status, or `null` on failure.
 */
data class NetworkLatencyResult(
    val name: String,
    val latencyMillis: Long?,
)
