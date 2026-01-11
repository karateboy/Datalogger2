FROM node:17.9.1
LABEL authors="karateboy"
WORKDIR /app
COPY .. /app
EXPOSE 8080
RUN yarn install
ENV NODE_OPTIONS=--openssl-legacy-provider
CMD ["yarn", "serve"]
