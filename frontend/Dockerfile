# ---------- Etape 1 : build ----------
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json ./
RUN npm install

COPY . .
RUN npm run build

# ---------- Etape 2 : serveur statique ----------
FROM nginx:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html

# Les deux configurations sont embarquees ; l'entrypoint choisit la bonne
# selon la presence de certificats montes.
COPY nginx/locations.conf /etc/nginx/snippets/locations.conf
COPY nginx/http.conf /etc/nginx/templates/http.conf
COPY nginx/https.conf /etc/nginx/templates/https.conf
COPY docker-entrypoint.sh /docker-entrypoint-creditflow.sh

RUN chmod +x /docker-entrypoint-creditflow.sh && mkdir -p /etc/nginx/certs

EXPOSE 80 443

# Adresse explicite plutot que "localhost" : insensible a l'ordre de resolution IPv4/IPv6
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
    CMD wget -q --spider http://127.0.0.1/ || wget -q --spider http://127.0.0.1/healthz || exit 1

ENTRYPOINT ["/docker-entrypoint-creditflow.sh"]
CMD ["nginx", "-g", "daemon off;"]
