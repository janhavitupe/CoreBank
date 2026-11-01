# One image per service: docker build --build-arg SERVICE=account-service -t corebank/account-service .
# Build the jars first with: mvn -DskipTests package
FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
COPY ${SERVICE}/target/${SERVICE}-1.0.0.jar /app.jar
RUN adduser -D -H corebank
USER corebank
ENV JAVA_OPTS="-Xmx200m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app.jar"]
