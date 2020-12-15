FROM lolhens/sbt-graal:graal-20.3.0-java11 as builder
MAINTAINER LolHens <pierrekisters@gmail.com>
COPY . .
ARG CI_VERSION=
RUN sbt graalvm-native-image:packageBin
RUN cp target/graalvm-native-image/satip-rtsp-proxy* satip-rtsp-proxy

FROM debian:10-slim
COPY --from=builder /root/satip-rtsp-proxy .
CMD exec ./satip-rtsp-proxy
