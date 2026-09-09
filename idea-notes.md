# DeltaBrief - MVP

## Główny problem
Śledzenie długotrwałych tematów newsowych, takich jak wojny, katastrofy, polityka zagraniczna, polityka wewnętrzna, relacje międzynarodowe, gospodarka, regulacje czy ważne wydarzenia społeczne, jest czasochłonne i przebodźcowujące.

Klasyczne serwisy newsowe oraz feedy pokazują głównie to, co jest najnowsze, najczęściej komentowane albo najbardziej klikalne. Użytkownik, który nie ma czasu codziennie śledzić danego tematu, nie potrzebuje kolejnej listy newsów. Potrzebuje odpowiedzi na pytanie:

**Co realnie zmieniło się od ostatniego razu, kiedy sprawdzałem ten temat?**

DeltaBrief rozwiązuje ten problem przez generowanie krótkich briefingów zmian dla wybranych tematów. Aplikacja nie próbuje pokazywać wszystkiego. Jej celem jest oddzielenie istotnych zmian od szumu informacyjnego.

## Opis rozwiązania
DeltaBrief to webowa aplikacja AI do śledzenia długotrwałych tematów newsowych. Użytkownik wybiera tematy, które chce obserwować, np. „wojna w Ukrainie”, „polityka USA”, „relacje USA-Chiny”, „polska polityka wewnętrzna” albo „sytuacja gospodarcza w Europie”.

Aplikacja cyklicznie analizuje najnowsze treści z wybranych źródeł i generuje tzw. **delta briefing**, czyli podsumowanie tego, co istotnie zmieniło się od poprzedniego briefingu.

Zamiast odpowiadać na pytanie „co nowego?”, DeltaBrief odpowiada na pytanie:

**Czy coś zmieniło obraz sytuacji?**

## Najmniejszy zestaw funkcjonalności
- Prosty system kont użytkowników do przechowywania tematów, preferencji i historii briefingów
- Tworzenie obserwowanych tematów newsowych przez użytkownika
- Możliwość dodania opisu celu obserwowania tematu, np. „chcę rozumieć najważniejsze zmiany bez codziennego śledzenia newsów”
- Dodawanie źródeł informacji, np. linków RSS lub predefiniowanych źródeł tematycznych
- Wybór częstotliwości generowania briefingu:
  - manualnie, na żądanie użytkownika
  - dwa razy dziennie
  - codziennie
  - co drugi dzień
  - raz w tygodniu
- Generowanie przez AI delta briefingu na podstawie najnowszych treści z wybranych źródeł
- Porównanie nowego briefingu z poprzednim briefingiem dla tego samego tematu
- Rodzaj 'onboarding briefingu' jako dotychczasowe summary tematu, nieco dłuższe niż kolejne delta breifngi - generowane jednorazowo na początku kiedy użytkownik chce zacząć śledzić dany temat
- Wskazanie najważniejszych zmian od poprzedniego briefingu
- Oznaczenie informacji, które są kontynuacją dotychczasowego trendu, a nie realną zmianą sytuacji
- Oznaczenie informacji, które mogą być szumem, spekulacją albo powtarzaniem znanych już faktów
- Wyjaśnienie, dlaczego dana zmiana może być istotna
- Wskazanie najważniejszych niepewności lub brakujących informacji
- Podanie źródeł, na których opiera się briefing
- Publikowanie wygenerowanego briefingu w webowej aplikacji użytkownika
- Wysyłanie wygenerowanego briefingu na adres e-mail użytkownika
- Przeglądanie historii briefingów dla każdego obserwowanego tematu
- Możliwość oceny briefingu przez użytkownika, np.:
  - przydatne
  - za dużo szumu
  - za mało kontekstu
  - to już wiedziałem
  - nieistotne dla mnie

## Przykładowy format briefingu
```md
# DeltaBrief: Wojna w Ukrainie
Okres: od poprzedniego briefingu, 7 dni temu

## Co realnie się zmieniło?
- Najważniejsza zmiana 1
- Najważniejsza zmiana 2

## Co jest kontynuacją dotychczasowego trendu?
- Informacja, która jest ważna, ale nie zmienia obrazu sytuacji

## Co wygląda na szum lub spekulację?
- Informacja powtarzana przez media, ale bez istotnych nowych faktów

## Dlaczego to ma znaczenie?
- Krótkie wyjaśnienie wpływu zmian na dalszy rozwój sytuacji

## Co pozostaje niepewne?
- Najważniejsze pytania bez jednoznacznej odpowiedzi

## Co zmienia możliwe scenariusze
- ...

## Źródła
- Źródło 1
- Źródło 2
- Źródło 3
```

## Co NIE wchodzi w zakres MVP
- Klasyczny, nieskończony feed newsów
- Publiczne publikowanie briefingów w social mediach
- Automatyczne podejmowanie decyzji lub rekomendowanie działań politycznych, inwestycyjnych albo prawnych
- Zaawansowany system oceny wiarygodności i biasu każdego źródła
- Pełna analiza wszystkich perspektyw politycznych danego tematu
- Integracje ze Slackiem, Discordem, Telegramem lub innymi komunikatorami
- Powiadomienia push i aplikacje mobilne
- Import treści z płatnych źródeł lub serwisów wymagających logowania
- Zaawansowane parsowanie pełnych artykułów z każdej strony internetowej
- Własny model AI trenowany na danych użytkownika
- Wieloosobowe organizacje, zespoły i współdzielone briefing rooms
- Alerty w czasie rzeczywistym dla tematów kryzysowych
- Zaawansowane wykrywanie dezinformacji

## Główny wyróżnik względem klasycznych digestów
DeltaBrief nie jest kolejnym AI digestem, który streszcza najnowsze newsy.

Klasyczny digest odpowiada na pytanie:

**Co nowego pojawiło się w źródłach?**

DeltaBrief odpowiada na pytanie:

**Co istotnie zmieniło się od poprzedniego briefingu i czy zmienia to obraz sytuacji?**

Najważniejsze wyróżniki:
- podejście „delta”, czyli porównywanie nowego stanu tematu z poprzednim briefingiem
- skupienie na długotrwałych tematach, a nie pojedynczych newsach
- ograniczanie szumu informacyjnego zamiast zwiększania liczby przeczytanych treści
- format decyzyjno-analityczny: zmiana, znaczenie, niepewność, źródła
- możliwość dopasowania częstotliwości briefingu do charakteru tematu
- delivery przez web appkę i e-mail, bez konieczności codziennego scrollowania

## Kryteria sukcesu
- Użytkownik jest w stanie utworzyć pierwszy obserwowany temat w mniej niż 3 minuty
- Użytkownik jest w stanie wygenerować pierwszy delta briefing dla tematu
- Wygenerowany briefing zawiera maksymalnie 5 najważniejszych zmian
- Co najmniej 70% pozycji w briefingu jest oceniane przez użytkownika jako przydatne
- Co najmniej 60% użytkowników wraca do wygenerowania kolejnego briefingu dla tego samego tematu
- Co najmniej 50% użytkowników decyduje się wysłać briefing na swój adres e-mail
- Użytkownik deklaruje, że briefing pomaga mu zrozumieć rozwój tematu bez codziennego śledzenia wielu źródeł
- Użytkownik potrafi wskazać, co zmieniło się od poprzedniego briefingu, bez czytania pełnych artykułów źródłowych
