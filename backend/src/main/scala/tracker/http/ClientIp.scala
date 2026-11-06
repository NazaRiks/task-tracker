package tracker.http

import cats.effect.IO
import com.comcast.ip4s.{Cidr, IpAddress, Ipv4Address, Ipv6Address}
import org.http4s.Request
import org.typelevel.ci.*

object ClientIp:
  private val ForwardedFor = ci"X-Forwarded-For"

  /** Адрес клиента как ключ для лимитов.
    *
    * По умолчанию это адрес соединения. Из X-Forwarded-For берётся последнее значение: его дописал
    * наш прокси, а всё, что левее, мог подставить сам клиент.
    */
  def key(request: Request[IO], trustForwardedFor: Boolean): String =
    val forwarded =
      if trustForwardedFor then
        request.headers
          .get(ForwardedFor)
          .flatMap(_.last.value.split(',').lastOption)
          .flatMap(value => IpAddress.fromString(value.strip))
      else None

    forwarded.orElse(request.remoteAddr).map(normalize).getOrElse("unknown")

  /** Одному клиенту обычно выдают целую подсеть IPv6 /64: без такого сведения он получал бы новый
    * лимит с каждого адреса.
    */
  private def normalize(address: IpAddress): String =
    address.collapseMappedV4 match
      case v4: Ipv4Address => v4.toString
      case v6: Ipv6Address => Cidr(v6, 64).prefix.toString
