# Этап сборки бэкенда. Сборка фронта добавится первой стадией на этапе 5.
FROM sbtscala/scala-sbt:eclipse-temurin-25.0.5_8_2.x AS backend-build
WORKDIR /build
# Сначала только описание сборки: слой с зависимостями не пересобирается при правках в src.
COPY backend/build.sbt ./
COPY backend/project/build.properties backend/project/plugins.sbt project/
RUN sbt update
COPY backend/src src
RUN sbt stage && cp -r target/out/jvm/scala-*/tracker-backend/universal/stage /stage

# Рантайм: только JRE и jar-файлы, без компилятора и sbt.
FROM eclipse-temurin:25.0.5_8-jre-alpine-3.24
RUN addgroup -S app && adduser -S -G app -H app
COPY --from=backend-build /stage/lib /app/lib
USER app
EXPOSE 8080
# Запуск напрямую, а не через скрипт native-packager: тому нужен bash, которого нет в alpine.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/app/lib/*", "tracker.Main"]
